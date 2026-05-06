# MineAstr NeoForge Mod

Server-side NeoForge 1.21.1 mod for bridging Minecraft chat to AstrBot.

## Build

Install JDK 21, then run:

```powershell
.\gradlew.bat build
```

The jar is written to `build/libs/`.

## Runtime

Install the built jar only on the NeoForge server. Clients do not need the mod.

After first server start, edit the generated common config and keep these values aligned with the AstrBot plugin:

```toml
enabled = true
websocketUrl = "ws://127.0.0.1:8765/ws"
token = "change-me"
serverId = "minecraft"
serverName = "Minecraft Server"
botDisplayName = "AstrBot"
reconnectSeconds = 5
maxMessageLength = 1000
```

Commands:

- `/mineastr status`
- `/mineastr reconnect`

Both commands require permission level 2.
