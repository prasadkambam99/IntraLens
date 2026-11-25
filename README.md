# Intraday Lens

A personal Android app for researching intraday trades on NSE stocks. Everything runs on the phone: there is no server, no broker account and no API key.

> Research and paper-testing only. This is not investment advice.

## What it does

| Screen | What you get |
|---|---|
| **Today** | At 9:30 AM, picks ranked by AI confidence %. Each pick shows BUY/SELL, the entry price, stop-loss, target, risk %, quantity for your ₹ risk and its live status (waiting / in trade / target hit / SL hit). |
| **Pick detail** | The trade plan step by step, a 5-min candle chart with entry/SL/target/VWAP lines, the reasons for the pick, which factors raised or lowered the %, key levels (prev H/L/C, pivot, R1/S1, ATR, RSI) and the last 2 days of news headlines with their tone. |
| **Watchlist** | Before the market opens: stocks set up for a bigger move (NR7, near 20-day high/low, volume spike, trend). |
| **Journal** | Every day's picks are saved automatically at about 9:30 AM, before anyone knows how they'll turn out, and then checked against real prices. Shows win rate, total R and the ₹ result. This screen is your 2-month test. |
| **AI Model** | An honest test on days the AI never saw: its hit rate vs. random breakouts, whether its % is trustworthy (calibration), and what it learned. |
| **Settings** | Risk per trade, minimum confidence, number of picks, alerts, and the list of stocks. |

Alerts (every 15 min while the market is open): picks ready, entry triggered, target hit, stop-loss hit.

## The strategy and the AI

* **Setup:** a 15-minute opening range breakout.
  * BUY when price breaks above the high of 9:15–9:30. SELL (short) when it breaks below the low.
  * The stop-loss goes on the other side of the range, between 0.35% and 1.2% away.
  * The target is 1.5× the risk.
  * Entries are taken only until 1:00 PM, and every trade is closed at 3:15 PM.
* **AI:** a small logistic-regression model trained on the phone. Every day it re-learns from the last ~60 days of real 5-minute prices for all stocks: was each past breakout a winner? It learns which conditions predicted wins, and the app keeps more history every day.
  * The 14 conditions it looks at: gap, range size, opening volume, VWAP, trend, RSI, NIFTY direction, room to yesterday's high/low and others.
* **The %:** the chance of hitting the target before the stop-loss *once the entry triggers*. With a 1.5R target, **break-even is 40%**. A pick at 60% means "expected +0.5R", not "sure win".
* **Data:** prices come from Yahoo Finance (free, may be a few minutes delayed) and news from Google News RSS.

## Build the APK (pick one)

### Option A: Android Studio (on your laptop)
1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** → select the `IntradayLens` folder. Wait for "Gradle sync" to finish (the first time takes 5–10 minutes).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and open it. Allow "install unknown apps" when asked.
   *Or* connect your phone with USB debugging on and press ▶ Run.

### Option B: GitHub builds it for you (no Android Studio)
1. Create a new **private** repository on github.com.
2. Upload all files in this folder, including the hidden `.github` folder. Using `git push` is easiest, because macOS Finder hides dot-folders when you drag and drop.
3. Open the **Actions** tab → "Build APK" → when it turns green, download **IntradayLens-apk** (a zip with the APK inside).

The project includes a fixed personal signing key (`app/personal.keystore`), so every new build installs as an **update** and your journal is kept.

## Daily use
1. Open the app any time. The first run downloads ~60 days of data and trains the AI (1–2 minutes on Wi-Fi).
2. Before 9:15, check the **Watchlist**.
3. After 9:30, look at the **Today** picks. Tap a pick for its full plan.
4. Each evening, check the **Journal**.

On Android, set battery usage for the app to "Unrestricted" so background alerts arrive on time.

## The 2-month test: how to judge it
* You need **30+ triggered trades** before the numbers mean anything.
* Look at **Avg R per trade** in the Journal. After brokerage and taxes it needs to be clearly above 0, roughly +0.15R or more.
* Check the AI Model screen to see whether the calibration table matches what really happened.
* If the results are flat, try fewer picks (raise the minimum confidence) or a different stock list before trusting it with money.

## Ideas for later
Long-term stock scoring, an on-device LLM news summary (e.g. Gemma via MediaPipe), more strategies (VWAP pullback, gap fill), sector strength, and exporting the journal to CSV.
