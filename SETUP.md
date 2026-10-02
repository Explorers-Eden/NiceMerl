# Setting up NiceMerl

<p align="center">
  <img src="assets/merl_reading.jpg" alt="Merl reading a book" width="320">
</p>

This guide takes you from nothing to a running NiceMerl in your Discord server, in about 15 minutes. You'll need:

- Admin rights on the Discord server
- A GitHub repo for this project (e.g. `Explorers-Eden/nicemerl`) and a Docker Hub account
- Your server with **Portainer** and **Watchtower** running

How updates flow:

```
git push to main ──► GitHub Actions builds niceron/nicemerl:latest ──► Docker Hub ──► Watchtower pulls it ──► NiceMerl restarts
```

GitHub also rebuilds the image every 6 hours to pick up security updates for Python and its packages. If nothing changed, the rebuilt image is identical, so Watchtower leaves the running bot alone. NiceMerl re-reads the **wiki** on her own every 6 hours, so wiki edits never need a new image.

---

## 1. Create the Discord bot

1. Go to <https://discord.com/developers/applications> and click **New Application**. Name it **NiceMerl** and click **Create**.
2. On the **General Information** page:
   - Upload [`assets/avatar.png`](assets/avatar.png) as the **App Icon**.
   - Optional description: *"Ask me anything about Explorer's Eden! A fan homage to Merl from Minecraft Earth."*
3. In the left sidebar, open **Bot**:
   - Set the **Username** to `NiceMerl` and upload [`assets/avatar.png`](assets/avatar.png) as the bot's **Icon**.
   - Under **Privileged Gateway Intents**, turn on **Message Content Intent** and click **Save Changes**.
     > ⚠️ NiceMerl can't read questions without this intent.
   - Click **Reset Token**, then **Copy**, and keep the token somewhere safe for step 4.
     > 🔒 The token is only shown once, and anyone who has it can control the bot. Never commit it or post it anywhere.
4. In the left sidebar, open **OAuth2 → URL Generator**:
   - Under **Scopes**, tick `bot`.
   - Under **Bot Permissions**, tick:
     - View Channels
     - Send Messages
     - Embed Links
     - Attach Files *(for Merl's pictures)*
     - Read Message History
   - Copy the generated URL at the bottom, open it in your browser, pick your server and click **Authorize**.

NiceMerl should now appear (offline) in your server's member list.

## 2. Get the channel ID

1. In Discord, open **User Settings → Advanced** and turn on **Developer Mode**.
2. Right-click the channel NiceMerl should answer in (e.g. `#ask-merl`) and choose **Copy Channel ID**.
3. If the channel is private, open **Edit Channel → Permissions**, add the **NiceMerl** role, and allow the permissions from step 1.4.

## 3. Push the project to GitHub

1. Create a repository on GitHub, e.g. **Explorers-Eden/nicemerl** (private is fine).
2. Push this folder to it:
   ```sh
   cd discord_bot
   git init -b main
   git add .
   git commit -m "NiceMerl"
   git remote add origin https://github.com/Explorers-Eden/nicemerl.git
   git push -u origin main
   ```
   `.env` and `.venv` are already in `.gitignore`, so the token can't be committed by accident.
3. In the repo, open **Settings → Secrets and variables → Actions → New repository secret** and add:

   | Name | Value |
   |---|---|
   | `DOCKER_USERNAME` | `niceron` |
   | `DOCKER_PASSWORD` | a Docker Hub **access token** (Docker Hub → Account settings → Personal access tokens, *Read & Write*) |

   > 💡 If the org already has these as organization secrets (the old enchantments site used the same names), you can skip this.
4. Open the **Actions** tab, select **Build and Push Docker Image**, then **Run workflow**. After 1–2 minutes, `niceron/nicemerl:latest` should appear on Docker Hub.

## 4. Create the Portainer stack

1. In Portainer, open **Stacks → Add stack**.
2. Name it `nicemerl`.
3. Under **Build method**, choose **Web editor** and paste the contents of [`portainer-stack.yml`](portainer-stack.yml).
4. Under **Environment variables**, click **Add an environment variable** twice and fill in:

   | Name | Value |
   |---|---|
   | `DISCORD_TOKEN` | the token from step 1.3 |
   | `CHANNEL_ID` | the channel ID from step 2 |

   Optional, with their defaults:

   | Name | Default | What it does |
   |---|---|---|
   | `WIKI_URL` | `https://wiki.explorerseden.eu` | Wiki to search |
   | `REINDEX_HOURS` | `6` | How often NiceMerl re-reads the wiki |
   | `RESULTS` | `3` | Pages shown per answer |
   | `COOLDOWN_SECONDS` | `5` | Minimum time between questions per user |

5. Click **Deploy the stack**.

> 💡 If the Docker Hub image is **private**, first add Docker Hub under **Registries** in Portainer, and make sure Watchtower has the login too (e.g. by mounting `~/.docker/config.json` into it). A public image needs neither.

## 5. Check that she's running

In Portainer, open **Containers → nicemerl → Logs**. After a few seconds you should see something like:

```
Logged in as NiceMerl#1234, answering in channel 123456789012345678
Fetched 1500 sections from 222 pages
```

NiceMerl now runs permanently and restarts on her own after crashes or reboots.

**Watchtower:** if your Watchtower runs with `--label-enable` (it only updates labelled containers), the stack already sets `com.centurylinklabs.watchtower.enable=true`. Otherwise it picks NiceMerl up automatically.

## 6. Say hi

<img src="assets/thumb_hello.png" alt="Merl waving" width="120" align="right">

Post these in the channel:

| You write | NiceMerl should… |
|---|---|
| `hi merl` | wave back and explain what she does |
| `how do I get a boss key` | link Boss Key, Armory and Raj Raksha, with excerpts |
| `asdfgh` | say she couldn't find anything (with Peanut Butter) |
| `!reindex` *(needs Manage Server)* | reply "Reindexed 222 pages." |

<br clear="right">

---

## Everyday tasks

| What | How |
|---|---|
| Change the bot's code | push to `main`. GitHub builds a new image and Watchtower deploys it on its next check (with the website's once-a-day Watchtower schedule, within a day). |
| Force a rebuild | GitHub **Actions → Build and Push Docker Image → Run workflow** |
| Roll back | set the stack image to an older `niceron/nicemerl:sha-<commit>` tag |
| Change the token/channel | Portainer **Stacks → nicemerl → Environment variables**, then **Update the stack** |
| Follow the logs | Portainer **Containers → nicemerl → Logs** |
| Restart | Portainer **Containers → nicemerl → Restart** |
| Pick up wiki edits right away | type `!reindex` in NiceMerl's channel *(needs Manage Server)* |

After you edit the wiki, NiceMerl picks up the changes within 6 hours, or right away with `!reindex`.

### Running without Portainer

Clone the repo on any machine with Docker, then:

```sh
cp .env.example .env   # fill in DISCORD_TOKEN and CHANNEL_ID
docker compose up -d --build
```

## Troubleshooting

<img src="assets/thumb_notfound.png" alt="Merl and Peanut Butter" width="120" align="right">

- **NiceMerl is online but never replies:**
  - Check that **Message Content Intent** is on (step 1.3).
  - Check that `CHANNEL_ID` is right.
  - Check that she can see and post in that channel.
- **Replies have no picture:** she's missing the **Attach Files** permission in that channel.
- **NiceMerl stays offline:** check the container logs. *Improper token* means `DISCORD_TOKEN` is wrong or was reset (step 1.3).
- **"DISCORD_TOKEN and CHANNEL_ID must be set":** the environment variables are missing from the Portainer stack (step 4.4).
- **The GitHub workflow fails at "Login to Docker Hub":** the `DOCKER_USERNAME`/`DOCKER_PASSWORD` secrets are missing or the access token expired (step 3.3).
- **Portainer can't pull the image:** the image is private and Portainer has no Docker Hub registry login (step 4), or the workflow hasn't run yet.
- **Code changes don't show up:** check that the workflow run went green in GitHub's Actions tab, then give Watchtower until its next check.
- **Everything is "couldn't find" right after a restart:** she's still reading the wiki. Give her about 10 seconds.

<br clear="right">

---

<sub>Merl and Peanut Butter are characters from *Minecraft Earth* © Mojang Studios. Images via the [Minecraft Wiki](https://minecraft.wiki/w/Earth:Merl). NiceMerl is an unofficial fan homage and isn't affiliated with or endorsed by Mojang or Microsoft.</sub>
