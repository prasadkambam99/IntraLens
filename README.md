# Intraday Lens 2.0

A personal Android app for researching intraday trades on NIFTY 50, BANK NIFTY and large NSE stocks. Everything runs on the phone: there is no server and no broker account.

> Research and paper-testing only. This is not investment advice.

## How to use it (every market day)
| Time | What the app shows | What you do |
|---|---|---|
| Before 9:15 | **Get ready** | Nothing. You can look at the Watchlist (in Settings). |
| 9:15–9:30 | **Wait** | No trades. The app is watching the first 15 minutes. |
| 9:30–1:00 | **Entry time**: each card shows **Wait**, **Buy now** or **Skip today** | Act only on **Buy now**. A loud alarm rings when it's time. |
| 1:00–3:15 | **Manage trades** | No new entries. Follow **Hold**, or **Hold — no-loss stop**. |
| 3:15 | **Exit everything** | Close all open trades. An alarm rings at 3:10. |

### Action words
* **Wait:** the price hasn't confirmed the breakout yet.
* **Buy now / Sell now:** enter now. The card shows the stop-loss and target.
* **Hold:** you're in the trade.
* **Hold — no-loss stop:** price has gone halfway to the target. Move your stop-loss to your entry price, so the trade can no longer lose.
* **Done:** the trade is finished. It shows target hit, stop-loss hit, exited at cost or closed at 3:15.
* **Skip today:** the setup is too weak, it's a wild day, or you've hit the loss limit.

### Strength labels (instead of %)
**Strong**, **Good**, **Weak** and **Skip** show how far the AI's chance is above break-even. Only Strong and Good can become **Buy now**. With **Strict mode** on in Settings, only Strong can.

## NIFTY and BANK NIFTY
* The stop-loss and target are fixed at 20 points each by default. You can change both in Settings.
* A BUY signal shows as **CALL (CE)** and a SELL signal as **PUT (PE)**. The suggested strike is the one nearest the price (at the money).
* The levels are on the **index chart**. An at-the-money option moves roughly half as many points as the index.

## Accuracy features
* **Confirmation:** a trade enters only when a 5-minute candle *closes* beyond the level, not just touches it.
* **Volume check:** stock breakouts need decent volume.
* **More signals:** the AI also looks at India VIX, the stock's sector index and NIFTY's direction.
* **Smarter AI:** each day it trains a simple model and a boosted-trees model, tests both on unseen days, and keeps whichever was best.
* **1-minute data** for the indices, so a 20-point stop and target are checked accurately.

## Safety features
* **Safe-day filter:** everything becomes "Skip today" when India VIX is high or jumping, or NIFTY opens with a big gap.
* **Daily loss limit:** after 2 stop-losses, the app says "Stop trading today". You can change the number in Settings.
* **No-loss stop:** the app tells you when to move your stop-loss to your entry price.
* **Event warnings:** RBI policy, Budget, Fed or company results news shows a warning.

## Other features
* **Results tab:** every pick is saved at 9:30 AM, before anyone knows the outcome. Tap **"I took this trade"** to also track **My trades** separately.
* **Weekly report card:** a notification every Friday evening.
* **Home-screen widget:** shows NIFTY and BANK NIFTY's action word. Add it by long-pressing the home screen → Widgets → Intraday Lens.

## Build the APK
**GitHub Actions:** push this folder to a private repository. Then go to **Actions** → **Build APK**, and download **IntradayLens-apk** from the finished run.
**Android Studio:** open the folder, then **Build → Build APK(s)**.
Every build is signed with the same personal key, so it installs over the old version and keeps your results.

## Phone settings (important)
* Allow notifications.
* Set battery usage for the app to **Unrestricted**, so alarms and live tracking aren't stopped by the phone.
* Open the app once after 9:00 AM on market days to start live alerts.
