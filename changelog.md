---v1.0

# Major changes
- Reordered default lyrics provider priority to BetterLyrics, Paxsenix, LrcLib, KuGou, LyricsPlus
- Fixed BetterLyrics being skipped even when set as top priority, caused by a provider timeout that was too short for it to respond in time
- Lyrics cache is now cleared automatically whenever lyrics provider settings change, so re-ordering priority takes effect immediately instead of only for newly played songs
- Songs downloaded for offline playback now automatically fetch and cache their lyrics too, including a one-time backfill for songs downloaded previously
- Fixed downloaded songs still showing "Lyrics not found" while offline

## Notable new features
- Song queue can now be freely reordered by default (previously locked)
- Replaced the previous/play/pause/next player controls with new Heroicons-based icons

## Other improvements
- Fixed lyrics text overlapping the camera cutout on the landscape lyrics page
- Fixed screen rotation on the Music Player and Lyrics pages sending the user back to Home instead of staying on the current page
- Fixed the mini player overlapping the bottom navigation bar after closing the full player
- Fixed auto-hidden playback controls staying hidden after rotating from portrait to landscape on the Lyrics page
