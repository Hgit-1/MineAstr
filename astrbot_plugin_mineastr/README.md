# MineAstr AstrBot Plugin

MineAstr exposes a `minecraft` platform adapter for AstrBot. The adapter starts a WebSocket server and accepts connections from the MineAstr NeoForge server mod.

Default endpoint:

```text
ws://127.0.0.1:8765/ws
Authorization: Bearer change-me
```

All Minecraft player chat is converted into one AstrBot group conversation:

- platform: `minecraft`
- group/session id: `minecraft`
- sender: Minecraft player UUID and name

AstrBot replies are sent back to every connected Minecraft server and are broadcast in game as `[AstrBot] <message>`.

## Setup

1. Copy or symlink `astrbot_plugin_mineastr` into AstrBot's plugin directory.
2. Install plugin requirements if AstrBot does not install them automatically:

```bash
pip install -r astrbot_plugin_mineastr/requirements.txt
```

3. Enable the `minecraft` platform adapter in AstrBot WebUI.
4. Set the same `token`, host, port, and path in the Minecraft mod common config.

The plugin-level `_conf_schema.json` mirrors the adapter defaults for discoverability. The active WebSocket settings are the platform adapter settings shown in AstrBot WebUI.
