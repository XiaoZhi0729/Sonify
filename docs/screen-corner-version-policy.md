# Screen Corner Version Policy

Android 12 / API 31 and newer:
- Manual screen-corner setting section and startup calibration are not shown.
- WindowInsets RoundedCorner values are read from the Compose host view.
- Four corner radii are combined by maximum, in pixels, then converted to dp.
- Until root insets arrive, use 30dp; later null insets retain the last reading.
- Non-null insets with no corners resolve to zero. Saved manual values are ignored.
- An additive pre-draw observer refreshes radius without replacing apply-insets
  listeners; it writes Compose state only when the value changes and is removed
  when the composable leaves composition.

Older systems:
- Settings entry and initial calibration remain available.
- Saved manual radius is read reactively, including zero.
- Calibration uses a dedicated full-window Dialog with 5dp left/right/bottom
  shell margins. Navigation padding is inside the shell. Surface directly owns
  the changing shape; Backdrop is not used for this legacy-only preview.

Playback transition shell and retreating-page clips read stable State holders
containing the latest effective radius and pixels. Fully expanded settled shell
behavior remains unchanged.

Verification: JVM regression tests cover API boundary, max-radius selection,
unit conversion, explicit zero, manual fallback and setting changes. A Compose
instrumentation source checks updated State propagation into remembered derived
state. Device instrumentation and legacy-device visual testing require a test
device; compilation alone is not runtime acceptance.
