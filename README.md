# Democratic Commands

![Logo](logo.png)

> A Minecraft Forge mod that brings democracy to server administration! Non-OP players can propose OP commands that are voted on by all online players.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.2.0-orange.svg)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Features

- **Democratic Voting System** - Non-OP players can propose OP commands that require community approval
- **Automatic Command Interception** - Just type OP commands normally - the mod handles the voting automatically
- **Full Autocomplete Support** - Non-OP players see command suggestions for all OP commands
- **Configurable Voting Rules** - Set approval thresholds, minimum voters, timeout periods, and more
- **Comprehensive Audit Logging** - All votes are logged with timestamps, participants, and results
- **Smart Abstention Handling** - AFK players won't block votes from completing

## Installation

1. Download the latest release from the [Releases](https://github.com/yourusername/democratic-commands/releases) page
2. Place the `.jar` file in your server's `mods` folder
3. Ensure you're running Minecraft Forge 1.20.1 (version 47.2.0 or higher)
4. Start your server - the mod will generate a config file on first run

## How It Works

1. **Non-OP player** types an OP command (e.g., `/gamemode creative PlayerName`)
2. **Vote initiates** automatically - all online players are notified
3. **Players vote** using `/vote yes` or `/vote no`
4. **Vote concludes** when:
   - Everyone has voted, OR
   - The timeout period expires (default: 30 seconds)
5. **Command executes** if the vote passes based on configured thresholds

### Example Workflow

```
Player1: /weather clear
[Server]: ===== VOTE INITIATED =====
[Server]: Player1 wants to execute: /weather clear
[Server]: Vote using: [YES] [NO]
[Server]: Vote expires in 30 seconds

Player2: /vote yes
Player3: /vote yes
Player1: /vote yes

[Server]: ===== VOTE CONCLUDED =====
[Server]: YES: 3 | NO: 0 | Abstained: 0
[Server]: ✓ VOTE PASSED
[Server]: Command executed successfully!
```

## Commands

| Command        | Description                            | Permission Required |
| -------------- | -------------------------------------- | ------------------- |
| `/vote`        | Shows help and available vote commands | None                |
| `/vote yes`    | Vote YES on the current proposal       | None                |
| `/vote no`     | Vote NO on the current proposal        | None                |
| `/vote status` | Check the status of the current vote   | None                |

## Configuration

The configuration file is located at `config/democraticcommands-server.toml` and is created on first server start.

### Voting Settings

| Setting                        | Default | Description                                                      |
| ------------------------------ | ------- | ---------------------------------------------------------------- |
| `voteTimeout`                  | 30      | Time in seconds before a vote automatically concludes            |
| `minimumVoters`                | 2       | Minimum players online required to initiate a vote               |
| `minimumVotesRequired`         | 2       | Minimum actual votes (not abstentions) for a valid result        |
| `approvalThreshold`            | 0.5     | Percentage of YES votes required to pass (0.5 = 50%, 0.66 = 66%) |
| `requireMajorityParticipation` | true    | If true, requires >50% of online players to vote (not abstain)   |
| `countAbstentionsAsNo`         | false   | If true, abstentions count as NO votes                           |
| `logVotes`                     | true    | Enable logging of all votes to file                              |

### Command List

| Setting      | Default   | Description                                               |
| ------------ | --------- | --------------------------------------------------------- |
| `opCommands` | See below | List of commands that require voting when used by non-ops |

**Default OP Commands:**

```toml
opCommands = [
    "gamemode", "gamerule", "give", "tp", "teleport",
    "kill", "ban", "kick", "op", "deop", "whitelist",
    "difficulty", "stop", "setblock", "fill", "summon",
    "effect", "enchant", "weather", "time", "spawnpoint",
    "setworldspawn"
]
```

### Example Configuration

```toml
# Require 66% approval with at least 3 votes
voteTimeout = 45
minimumVoters = 3
minimumVotesRequired = 3
approvalThreshold = 0.66
requireMajorityParticipation = true
countAbstentionsAsNo = false
logVotes = true

# Add more commands to the voting system
opCommands = [
    "gamemode", "gamerule", "give", "tp", "teleport",
    "kill", "ban", "kick", "op", "deop", "whitelist",
    "difficulty", "stop", "setblock", "fill", "summon",
    "effect", "enchant", "weather", "time", "spawnpoint",
    "setworldspawn", "scoreboard", "team", "worldborder",
    "xp", "experience", "particle", "playsound"
]
```

## Vote Resolution Logic

A vote **passes** when ALL of the following conditions are met:

1. ✅ Total votes ≥ `minimumVotesRequired`
2. ✅ If `requireMajorityParticipation` is true: More than 50% of online players voted
3. ✅ YES votes ÷ (YES + NO votes) ≥ `approvalThreshold`
4. ✅ If `countAbstentionsAsNo` is true: Abstentions are counted as NO votes

A vote **fails** if any condition is not met, with clear feedback about why.

## Audit Logging

When `logVotes` is enabled, all votes are logged to `world/logs/audit.txt`:

```
[2024-01-15 14:32:45] Vote INITIATED
Command: /gamemode creative Player1
Initiator: Player2
YES votes (2): Player2, Player3
NO votes (1): Player4
Abstained (1): Player5
Result: PASSED
----------------------------------------
```

## Troubleshooting

### Players can't see OP command suggestions

- The mod automatically makes whitelisted OP commands visible to all players
- If suggestions aren't showing, check that the command is in the `opCommands` list

### Votes aren't triggering

- Ensure the command is in the `opCommands` configuration list
- Check that minimum voter requirements are met
- Verify the player doesn't already have OP permissions

### Commands execute without voting

- The command might not be in the `opCommands` list
- The player might have OP permissions (ops bypass voting)

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

Java isn't my first language, but it is my first mod, so there are probably several things that can be improved.

## License

This mod is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Credits

- Created by [d-pixie](https://github.com/d-pixie)
- Built with [Minecraft Forge](https://minecraftforge.net/)
