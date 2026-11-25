# Hands-on checklist (H1 + H2)

Everything that needs ears, hands on the headphones, or Sargemck's judgement. All milestones are built, so H1 and H2 can run as one session, about 60 min. Claude Code watches logcat throughout and writes results into FINDINGS.

**Setup:** M7 build installed, headphones worn, phone connected to the PC over USB, PC audio available. Close Quiet Rebellion and the Bose app.

## A. By ear (H1)

1. **Noise slider,** end to end. Left lets the world in, right blocks it. Compare the drag lag with Quiet Rebellion.
2. **Wind Block.** Turn it on, then move the slider. Does the slider still change anything? (CLAUDE.md says Wind Block masks it.)
3. **Noise cancelling switch.** What does turning it off do by ear? The copy gets updated to match.
4. **Spatial audio:** Off, Still, Motion. Turn your head. Still should stay put and Motion should move with you.
5. **EQ:** each preset, then custom bass, mid and treble. Should the EQ sliders write while dragging, like the noise slider?
6. **Self Voice** in a call or with the mic: Low, Medium, High. Is the mapping right?
7. **Custom mode:** create one from the current sound, edit it while it's current. Is the change heard at once? Then delete it.

## B. Hands on the headphones (H1)

1. Press the shortcut button, press and hold it, swipe volume, change mode with the button. Does the app follow? (Unsolicited frames show in the log.)
2. Take them off and put them back on. Auto play/pause, and does the app follow?
3. Turn them off and on. Does turning on need the power button, or do they wake on movement? That decides the power-off copy.
4. Power off from the app, then pairing mode from the app. Each shows the right state.

## C. Two sources (S1 follow-up)

Game or video on the PC.

1. A phone notification: is the PC audio cut, and for how long?
2. Phone media takes over, and the PC comes back about 4 s after it stops.
3. Spike `disconnect pc`, then a phone notification: the PC stays quiet and the phone is fine. Spike `connect-dev pc`: PC audio is back within about 5 s.
4. Spike: disconnect the phone itself ([4.2] with the phone's MAC). Does a notification still interrupt the PC? Then the app's Reconnect brings the phone back.

## D. Daily use (H2)

1. Quick Settings tile: add it from the All modes sheet, tap through the starred modes, long-press opens the app.
2. **Auto-connect:**
   - headphones off overnight, on in the morning, app never opened: notification and tile are live within about 10 s
   - reboot the phone, unlock, app never opened, headphones on: same
3. The app icon on the home screen and as a themed icon.
4. **UX walk-through:** anything confusing, ugly or missing. It becomes the v1.1 list.
5. Use it as the daily driver for a week and note annoyances.
