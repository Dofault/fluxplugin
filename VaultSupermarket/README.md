# Supermarket Plugin for Minecraft 1.21.4 papermc

A supermarket plugin for Minecraft: the player enters the shop, takes items, and must pay for them before leaving. Full management of inventories, item prices, and payments via Vault.

---

## Requirements

- Java 17
- Apache Maven 3.9.11
- Minecraft server compatible with Paper MC version 1.21.4
- Vault for economy management

---

| Command                        | Description                                      |
| ------------------------------ | ------------------------------------------------ |
| entry                          | Sets the shop entry location                     |
| exit                           | Sets the shop exit location                      |
| save                           | Saves the shop (entry and exit required)        |
| setprice <item> <price>        | Sets the price of an item                        |
| difference                     | Shows the difference with the saved inventory   |
| pay                            | Pays the player for the items taken             |
| addchest                       | Adds a chest to the shop                         |
| removechest                    | Removes the last added chest                     |
| additemchest <item> <amount>   | Adds an item to the last added chest            |

```bash
git clone <repo-url>
cd <project-name>
mvn dependency:resolve
mvn clean compile
mvn clean install