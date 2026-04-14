# LeaguesSync

A RuneLite plugin that syncs your Leagues task completion to the [OSRS League Tracker](https://osrsleaguetracker.com).

## What it does

Every 30 seconds while logged in, the plugin reads your completed league task IDs from the game client and submits them to your task tracker server. The web tracker then reflects your current progress in real time.

## Setup

1. Install the plugin from the Plugin Hub
2. In the plugin config panel, set the **Server URL** to your task tracker server (default points to the public instance)
3. Enable **Sync enabled**
4. Log in to OSRS during the league — syncing starts automatically

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| Server URL | `https://api.osrsleaguetracker.com/` | URL of the LeaguesSync server |
| Enable sync | `true` | Uncheck to pause syncing without disabling the plugin |

## Building locally

```bash
./gradlew build
./gradlew run   # launches RuneLite with the plugin loaded
```

## Licence

BSD 2-Clause — see [LICENSE](LICENSE).
