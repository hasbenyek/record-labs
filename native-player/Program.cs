using System;
using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using System.Threading;
using System.Runtime.InteropServices;
using System.Collections.Concurrent;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace RecordLabs.NativePlayer;

internal static class Program
{
    private static readonly object Gate = new();
    private static readonly NativeState State = new();
    private static MediaPlayer? player;
    private static volatile bool wantsToPlay;
    private static LowLevelKeyboardProc? keyboardProc;
    private static IntPtr keyboardHook = IntPtr.Zero;
    private static string? lastExternalCommand;
    private static long lastExternalCommandAt;
    private static BluetoothConnectBroker? bluetoothBroker;
    private static readonly ConcurrentQueue<string> BluetoothPackets = new();
    private static double desiredTempo = 1.0;
    private static int desiredPitchSemitones;

    private static async Task Main(string[] args)
    {
        if (args.Length < 1 || !int.TryParse(args[0], out var port))
        {
            Console.Error.WriteLine("Usage: RecordLabs.NativePlayer <loopback-port> [parent-pid]");
            return;
        }

        // Parent process watchdog: exit automatically if parent Java process closes
        if (args.Length >= 2 && int.TryParse(args[1], out var parentPid) && parentPid > 0)
        {
            _ = Task.Run(async () =>
            {
                while (true)
                {
                    await Task.Delay(1500);
                    try
                    {
                        var parent = System.Diagnostics.Process.GetProcessById(parentPid);
                        if (parent == null || parent.HasExited)
                        {
                            Environment.Exit(0);
                        }
                    }
                    catch
                    {
                        Environment.Exit(0);
                    }
                }
            });
        }

        player = new MediaPlayer();
        player.AutoPlay = false;
        player.AudioCategory = MediaPlayerAudioCategory.Media;
        player.AudioDeviceType = MediaPlayerAudioDeviceType.Multimedia;
        // Expose a real Windows media session. Hardware transport keys are
        // forwarded to the Kotlin queue controller through the status payload.
        player.CommandManager.IsEnabled = true;
        player.CommandManager.PlayReceived += (_, _) => EmitExternalCommand("play");
        player.CommandManager.PauseReceived += (_, _) => EmitExternalCommand("pause");
        player.CommandManager.NextReceived += (_, _) => EmitExternalCommand("next");
        player.CommandManager.PreviousReceived += (_, _) => EmitExternalCommand("previous");
        StartMediaKeyHook();
        bluetoothBroker = new BluetoothConnectBroker();
        bluetoothBroker.PacketReceived += packet => BluetoothPackets.Enqueue(packet);
        _ = bluetoothBroker.StartAsync();
        player.MediaOpened += (_, _) =>
        {
            ApplyAudioTuning();
            if (wantsToPlay) player.Play();
            else player.Pause();
            UpdateState(playing: wantsToPlay, ready: true, ended: false);
        };
        player.MediaEnded += (_, _) =>
        {
            wantsToPlay = false;
            UpdateState(playing: false, ended: true);
        };
        player.MediaFailed += (_, error) =>
        {
            wantsToPlay = false;
            Console.Error.WriteLine($"MediaFailed: {error.Error} - {error.ErrorMessage}");
            UpdateState(playing: false, ready: false, error: $"{error.Error}: {error.ErrorMessage}", ended: false);
        };
        player.PlaybackSession.PlaybackStateChanged += (_, _) =>
        {
            var state = player.PlaybackSession.PlaybackState;
            // Treat Opening, Buffering, and Playing as active play state so UI does not flicker pause
            var isPlaying = wantsToPlay &&
                (state == MediaPlaybackState.Playing || state == MediaPlaybackState.Buffering || state == MediaPlaybackState.Opening);
            UpdateState(playing: isPlaying);
        };
        player.PlaybackSession.PositionChanged += (_, _) => UpdateState();

        using var listener = new HttpListener();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        listener.Start();
        Console.WriteLine("ready");

        while (listener.IsListening)
        {
            var context = await listener.GetContextAsync();
            _ = Task.Run(() => Handle(context));
        }
    }

    private static void Handle(HttpListenerContext context)
    {
        try
        {
            switch (context.Request.Url?.AbsolutePath.Trim('/').ToLowerInvariant())
            {
                case "play":
                    var url = context.Request.QueryString["url"];
                    if (string.IsNullOrWhiteSpace(url)) throw new ArgumentException("Missing url");
                    wantsToPlay = true;
                    player!.Source = MediaSource.CreateFromUri(new Uri(url));
                    UpdateState(position: 0, duration: 0, playing: true, ready: false, error: null, ended: false);
                    WriteJson(context, new { ok = true });
                    break;
                case "pause":
                    wantsToPlay = false;
                    player!.Pause();
                    UpdateState(playing: false);
                    WriteJson(context, new { ok = true });
                    break;
                case "resume":
                    lock (Gate)
                    {
                        if (!string.IsNullOrWhiteSpace(State.Error))
                            throw new InvalidOperationException("Reload the track after a playback failure.");
                    }
                    wantsToPlay = true;
                    player!.Play();
                    UpdateState(playing: true, ended: false);
                    WriteJson(context, new { ok = true });
                    break;
                case "seek":
                    var position = long.Parse(context.Request.QueryString["positionMs"] ?? "0", CultureInfo.InvariantCulture);
                    player!.PlaybackSession.Position = TimeSpan.FromMilliseconds(Math.Max(0, position));
                    UpdateState(position: position);
                    WriteJson(context, new { ok = true });
                    break;
                case "rate":
                    var rate = double.Parse(context.Request.QueryString["value"] ?? "1", CultureInfo.InvariantCulture);
                    desiredTempo = Math.Clamp(rate, 0.5, 2.0);
                    desiredPitchSemitones = 0;
                    ApplyAudioTuning();
                    WriteJson(context, new { ok = true, rate = player.PlaybackSession.PlaybackRate });
                    break;
                case "tuning":
                    desiredTempo = Math.Clamp(
                        double.Parse(context.Request.QueryString["tempo"] ?? "1", CultureInfo.InvariantCulture),
                        0.5,
                        2.0
                    );
                    desiredPitchSemitones = Math.Clamp(
                        int.Parse(context.Request.QueryString["pitch"] ?? "0", CultureInfo.InvariantCulture),
                        -12,
                        12
                    );
                    ApplyAudioTuning();
                    WriteJson(context, new {
                        ok = true,
                        tempo = desiredTempo,
                        pitch = desiredPitchSemitones,
                        effectiveRate = player!.PlaybackSession.PlaybackRate,
                    });
                    break;
                case "volume":
                    var volume = double.Parse(context.Request.QueryString["value"] ?? "1", CultureInfo.InvariantCulture);
                    player!.Volume = Math.Clamp(volume, 0.0, 1.0);
                    WriteJson(context, new { ok = true, volume = player.Volume });
                    break;
                case "stop":
                    wantsToPlay = false;
                    player!.Source = null;
                    UpdateState(position: 0, duration: 0, playing: false, ready: false, ended: false);
                    WriteJson(context, new { ok = true });
                    break;
                case "exit":
                case "shutdown":
                    WriteJson(context, new { ok = true });
                    Environment.Exit(0);
                    break;
                case "status":
                    RefreshPosition();
                    var packets = new System.Collections.Generic.List<string>();
                    while (BluetoothPackets.TryDequeue(out var packet)) packets.Add(packet);
                    lock (Gate) WriteJson(context, new {
                        State.IsPlaying, State.IsReady, State.PositionMs, State.DurationMs, State.Error, State.Ended,
                        State.ExternalCommand, State.ExternalCommandSequence,
                        BluetoothPackets = packets,
                    });
                    break;
                case "connect-bluetooth-send":
                    var encoded = context.Request.QueryString["payload"];
                    if (string.IsNullOrWhiteSpace(encoded)) throw new ArgumentException("Missing payload");
                    bluetoothBroker?.Send(Encoding.UTF8.GetString(Convert.FromBase64String(encoded)));
                    WriteJson(context, new { ok = true });
                    break;
                default:
                    context.Response.StatusCode = 404;
                    WriteJson(context, new { error = "Unknown command" });
                    break;
            }
        }
        catch (Exception error)
        {
            UpdateState(playing: false, error: error.Message);
            context.Response.StatusCode = 500;
            WriteJson(context, new { error = error.Message });
        }
    }

    private static void ApplyAudioTuning()
    {
        if (player == null) return;
        // Media Foundation exposes playback-rate tuning at this layer. A
        // semitone is a 2^(1/12) frequency ratio; combining it with tempo gives
        // immediate audible pitch/tempo feedback without rebuilding the source.
        var pitchRatio = Math.Pow(2.0, desiredPitchSemitones / 12.0);
        player.PlaybackSession.PlaybackRate = Math.Clamp(desiredTempo * pitchRatio, 0.5, 2.0);
    }

    private static void RefreshPosition()
    {
        if (player == null) return;
        var state = player.PlaybackSession.PlaybackState;
        var isPlaying = wantsToPlay &&
            (state == MediaPlaybackState.Playing || state == MediaPlaybackState.Buffering || state == MediaPlaybackState.Opening);
        UpdateState(
            position: (long)player.PlaybackSession.Position.TotalMilliseconds,
            duration: (long)player.PlaybackSession.NaturalDuration.TotalMilliseconds,
            playing: isPlaying);
    }

    private static void EmitExternalCommand(string command)
    {
        lock (Gate)
        {
            var now = Environment.TickCount64;
            if (lastExternalCommand == command && now - lastExternalCommandAt < 280) return;
            lastExternalCommand = command;
            lastExternalCommandAt = now;
            State.ExternalCommand = command;
            State.ExternalCommandSequence++;
        }
    }

    // AWT does not consistently receive VK_MEDIA_* from laptop firmware.
    // Keep a tiny Win32 low-level hook in the native media process and relay
    // only dedicated transport keys through the existing status channel.
    private static void StartMediaKeyHook()
    {
        var hookThread = new Thread(() =>
        {
            keyboardProc = MediaKeyboardCallback;
            keyboardHook = SetWindowsHookEx(WH_KEYBOARD_LL, keyboardProc, GetModuleHandle(null), 0);
            while (GetMessage(out var message, IntPtr.Zero, 0, 0))
            {
                TranslateMessage(ref message);
                DispatchMessage(ref message);
            }
            if (keyboardHook != IntPtr.Zero) UnhookWindowsHookEx(keyboardHook);
        })
        {
            IsBackground = true,
            Name = "RecordLabs media-key hook",
        };
        hookThread.Start();
    }

    private static IntPtr MediaKeyboardCallback(int code, IntPtr wParam, IntPtr lParam)
    {
        if (code >= 0 && (wParam == (IntPtr)WM_KEYDOWN || wParam == (IntPtr)WM_SYSKEYDOWN))
        {
            var keyboard = Marshal.PtrToStructure<KbdLlHookStruct>(lParam);
            switch (keyboard.VirtualKey)
            {
                case VK_MEDIA_PLAY_PAUSE: EmitExternalCommand("play_pause"); break;
                case VK_MEDIA_STOP: EmitExternalCommand("stop"); break;
                case VK_MEDIA_NEXT_TRACK: EmitExternalCommand("next"); break;
                case VK_MEDIA_PREV_TRACK: EmitExternalCommand("previous"); break;
            }
        }
        return CallNextHookEx(keyboardHook, code, wParam, lParam);
    }

    private const int WH_KEYBOARD_LL = 13;
    private const int WM_KEYDOWN = 0x0100;
    private const int WM_SYSKEYDOWN = 0x0104;
    private const uint VK_MEDIA_NEXT_TRACK = 0xB0;
    private const uint VK_MEDIA_PREV_TRACK = 0xB1;
    private const uint VK_MEDIA_STOP = 0xB2;
    private const uint VK_MEDIA_PLAY_PAUSE = 0xB3;

    private delegate IntPtr LowLevelKeyboardProc(int code, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct KbdLlHookStruct
    {
        public uint VirtualKey;
        public uint ScanCode;
        public uint Flags;
        public uint Time;
        public UIntPtr ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X; public int Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeMessage
    {
        public IntPtr Window;
        public uint Message;
        public UIntPtr WParam;
        public IntPtr LParam;
        public uint Time;
        public Point Position;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int hookId, LowLevelKeyboardProc callback, IntPtr module, uint threadId);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")]
    private static extern bool GetMessage(out NativeMessage message, IntPtr window, uint min, uint max);
    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref NativeMessage message);
    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref NativeMessage message);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? moduleName);

    private static void UpdateState(long? position = null, long? duration = null, bool? playing = null, bool? ready = null, string? error = null, bool? ended = null)
    {
        lock (Gate)
        {
            if (position.HasValue) State.PositionMs = position.Value;
            if (duration.HasValue) State.DurationMs = duration.Value;
            if (playing.HasValue) State.IsPlaying = playing.Value;
            if (ready.HasValue) State.IsReady = ready.Value;
            if (error != null || ended.HasValue) State.Error = error;
            if (ended.HasValue) State.Ended = ended.Value;
        }
    }

    private static void WriteJson(HttpListenerContext context, object value)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value));
        context.Response.ContentType = "application/json";
        context.Response.ContentLength64 = bytes.Length;
        context.Response.OutputStream.Write(bytes, 0, bytes.Length);
        context.Response.Close();
    }

    private sealed class NativeState
    {
        public bool IsPlaying { get; set; }
        public bool IsReady { get; set; }
        public long PositionMs { get; set; }
        public long DurationMs { get; set; }
        public string? Error { get; set; }
        public bool Ended { get; set; }
        public string? ExternalCommand { get; set; }
        public long ExternalCommandSequence { get; set; }
    }
}
