using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace RecordLabs.NativePlayer;

/// <summary>
/// Small RFCOMM relay used only when LAN multicast cannot reach the user's
/// Android device. Payload authentication remains in the Kotlin protocol; the
/// broker only transports newline-delimited UTF-8 packets between paired
/// devices and never receives Google credentials.
/// </summary>
internal sealed class BluetoothConnectBroker : IDisposable
{
    internal static readonly Guid ServiceUuid = Guid.Parse("8d865bce-0b1f-4ee0-a85d-81d53b6f70a2");
    private readonly ConcurrentDictionary<string, Link> links = new();
    private readonly CancellationTokenSource cancellation = new();
    private StreamSocketListener? listener;
    private RfcommServiceProvider? provider;
    public event Action<string>? PacketReceived;

    public async Task StartAsync()
    {
        await StartServerAsync();
        _ = Task.Run(ScanLoopAsync);
    }

    private async Task StartServerAsync()
    {
        try
        {
            provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(ServiceUuid));
            listener = new StreamSocketListener();
            listener.ConnectionReceived += (_, args) => Attach(args.Socket, "incoming-" + Guid.NewGuid());
            await listener.BindServiceNameAsync(
                provider.ServiceId.AsString(),
                SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
            provider.StartAdvertising(listener, true);
        }
        catch (Exception error)
        {
            Console.Error.WriteLine("Bluetooth RFCOMM server unavailable: " + error.Message);
        }
    }

    private async Task ScanLoopAsync()
    {
        while (!cancellation.IsCancellationRequested)
        {
            try
            {
                var selector = RfcommDeviceService.GetDeviceSelector(RfcommServiceId.FromUuid(ServiceUuid));
                var devices = await DeviceInformation.FindAllAsync(selector);
                foreach (var device in devices)
                {
                    if (links.ContainsKey(device.Id)) continue;
                    var service = await RfcommDeviceService.FromIdAsync(device.Id);
                    if (service == null) continue;
                    var socket = new StreamSocket();
                    try
                    {
                        await socket.ConnectAsync(
                            service.ConnectionHostName,
                            service.ConnectionServiceName,
                            SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
                        Attach(socket, device.Id);
                    }
                    catch
                    {
                        socket.Dispose();
                    }
                }
            }
            catch (Exception error)
            {
                Console.Error.WriteLine("Bluetooth RFCOMM scan unavailable: " + error.Message);
            }
            try { await Task.Delay(5000, cancellation.Token); }
            catch (OperationCanceledException) { return; }
        }
    }

    private void Attach(StreamSocket socket, string id)
    {
        var link = new Link(socket, id, packet => PacketReceived?.Invoke(packet), () => links.TryRemove(id, out _));
        if (!links.TryAdd(id, link))
        {
            link.Dispose();
            return;
        }
        link.Start();
    }

    public void Send(string packet)
    {
        foreach (var link in links.Values.ToList()) _ = link.SendAsync(packet);
    }

    public void Dispose()
    {
        cancellation.Cancel();
        provider?.StopAdvertising();
        listener?.Dispose();
        foreach (var link in links.Values) link.Dispose();
        links.Clear();
        cancellation.Dispose();
    }

    private sealed class Link : IDisposable
    {
        private readonly StreamSocket socket;
        private readonly string id;
        private readonly Action<string> onPacket;
        private readonly Action onClosed;
        private readonly SemaphoreSlim writeGate = new(1, 1);
        private readonly CancellationTokenSource cancellation = new();

        public Link(StreamSocket socket, string id, Action<string> onPacket, Action onClosed)
        {
            this.socket = socket;
            this.id = id;
            this.onPacket = onPacket;
            this.onClosed = onClosed;
        }

        public void Start() => _ = Task.Run(ReadLoopAsync);

        private async Task ReadLoopAsync()
        {
            var reader = new DataReader(socket.InputStream) { InputStreamOptions = InputStreamOptions.Partial };
            var pending = "";
            try
            {
                while (!cancellation.IsCancellationRequested)
                {
                    var count = await reader.LoadAsync(4096).AsTask(cancellation.Token);
                    if (count == 0) break;
                    pending += reader.ReadString(count);
                    while (true)
                    {
                        var newline = pending.IndexOf('\n');
                        if (newline < 0) break;
                        var packet = pending[..newline].TrimEnd('\r');
                        pending = pending[(newline + 1)..];
                        if (!string.IsNullOrWhiteSpace(packet)) onPacket(packet);
                    }
                }
            }
            catch { }
            finally
            {
                reader.DetachStream();
                reader.Dispose();
                Dispose();
                onClosed();
            }
        }

        public async Task SendAsync(string packet)
        {
            await writeGate.WaitAsync();
            try
            {
                var writer = new DataWriter(socket.OutputStream) { UnicodeEncoding = UnicodeEncoding.Utf8 };
                writer.WriteString(packet + "\n");
                await writer.StoreAsync();
                await writer.FlushAsync();
                writer.DetachStream();
                writer.Dispose();
            }
            catch
            {
                Dispose();
                onClosed();
            }
            finally { writeGate.Release(); }
        }

        public void Dispose()
        {
            if (cancellation.IsCancellationRequested) return;
            cancellation.Cancel();
            socket.Dispose();
            writeGate.Dispose();
        }
    }
}
