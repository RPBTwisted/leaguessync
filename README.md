# LeaguesSync

A RuneLite plugin that syncs your Leagues task completion to the [OSRS League Tracker](https://osrsleaguetracker.com).

## What it does

Every 5 seconds while logged in, the plugin reads your completed league task IDs from the game client and submits them to the task tracker server. The web tracker then reflects your current progress in real time.

## Setup

1. Install the plugin from the Plugin Hub
2. Enable **Sync enabled** in the plugin config panel
3. Log in to OSRS during the league — syncing starts automatically

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| Enable sync | `true` | Uncheck to pause syncing without disabling the plugin |

## Support

Having issues? Join the [Discord](https://discord.gg/6qDtmKZh).

## Building locally

```bash
./gradlew build
./gradlew run   # launches RuneLite with the plugin loaded
```

## Licence

BSD 2-Clause — see [LICENSE](LICENSE).
