# Cresto Dialog Migration

## What glasense-ui Is

In Cresto-1.0-alpha1082, `glasense-ui` is a local Android Jetpack Compose
library (`com.nevoit.glasense`), included by `implementation(project(":glasense-ui"))`.
It supplies basic components, typography, colors, shape tokens and spring
animation helpers. It is not a renderer, WebView framework or published Maven
library in this snapshot. Cresto's README explicitly says it is unfinished and
not recommended for reuse yet.

The actual blurred dialog lives in the app module:
`app/src/main/java/com/nevoit/cresto/ui/components/glasense/GlasenseDialog.kt`.
Its material renderer also lives in app. Importing only the library would not
bring in these glass dialogs.

The library declares minSdk 31 and excludes Material3; Sonify supports minSdk
23 and uses Material3 throughout. Importing the complete library would impose
unnecessary framework and SDK changes. Sonify instead adapts the dialog,
thin-material shader and geometry/motion tokens to the existing Backdrop 1.0.5
and Shapes 1.0.0 dependencies.

## Implemented Scope

- SonifyDialog: centered surface, window width minus 96dp, 12dp padding,
  48dp minimum actions, 12dp action spacing. Original legacy title icons are
  omitted by OptionDialog so the header matches Cresto.
- BarBlurEffect selects Cresto's ordinary (24dp shell, 12dp rounded buttons,
  blur32dp) or liquid (36dp shell, capsule buttons, blur16dp + lens48dp/48dp)
  branch. Ordinary shells and primary actions use the original 1.5dp additive
  edge highlight; liquid shells use Backdrop highlight at 90 degrees.
- Dialog primary actions are original Blue500 #2B7FFF with white text.
  Destructive actions are original Red500 #FB2C36 with white text in both
  themes/materials. Cancel backgrounds use black/white at 5%, opaque text.
  Disabled background/text colors follow AppButtonColors independently.
- Press feedback uses original DimIndication timings (150ms in, 200ms out,
  minimum 5% flash for a short press), not the Material ripple.
- Wide picker: 16dp horizontal margins, width capped at 560dp.
- Entry: scale 1.15 to 1, critically damped spring derived from Cresto's 350ms
  helper, alpha 300ms. Exit: alpha 200ms before closing callbacks.
- Black 40% scrim and original Cresto thin-material AGSL color mapping.
  Both modes retain the same recipe. The liquid branch changes blur/lens,
  not alpha. Shadow uses original BlurMaskFilter32dp, downward offset16dp,
  black alpha10% light/20% dark. API below 33 or shader creation failure
  uses original opaque themed surface white/#1B1C1D.
- Back/outside taps are consumed by default as in the original; explicit
  actions dismiss the popup. Closing keeps action colors unchanged.
- Theme-level overlay host captures application content without the modal or
  scrim, registers current content through observable state and reads latest
  callbacks. Capture is active only while a dialog exists. Background semantics
  are cleared and focus entry blocked while the topmost dialog owns focus.
  Caller composition-local context is restored inside the overlay.
- Generic content/input slots, scrollable long body, fixed action section,
  keyboard/safe-area insets, caller-owned pending/error state.
- OptionDialog now delegates to SonifyDialog. SheetState was removed rather
  than silently ignored. The screen-corner editor saves
  after the new dialog's exit instead of waiting for a removed sheet to hide.
- Playlist create/delete/remove/add dialogs use the shared component.
- Queue deletion uses destructive action color. Multi-account selection uses
  the same shared shell without changing login logic.
- CAPTCHA retains its separate platform Dialog and WebView lifecycle. Only
  its shape is aligned, and its existing close icon is now actionable.
- Existing LiquidDropdown menus, sleep/speed controls and quality menus are
  unchanged. Existing toolbar-liquid-glass setting also selects popup material.

The ordinary and liquid popup appearance now follows Cresto's static palette
and both material branches. The complete UI library/dynamic-color selector is
not imported. Slot-based form/search content and the independent CAPTCHA window
are retained for Sonify-specific workflows.

## Attribution

Upstream identifies its developer as Nevoit (https://github.com/nevodev).
Cresto is Apache-2.0 licensed, compatible with this project's GPLv3 distribution
provided its license/notices are preserved. Source modifications are marked,
and the Apache license plus attribution are packaged under
`app/src/main/assets/licenses/`. The acknowledgements page credits
Cresto / Glasense. Blue500/Red500 tokens retain the original Tailwind MIT notice.

## Verification

Instrumentation tests cover overlay-host input/pending-state updates,
confirmation callback timing, caller composition-local preservation, and
long-content scrolling with visible actions. APK build and instrumentation
source compilation pass. JVM unit tests: 23 suites, 164 tests, zero failures or
errors, including five CrestoDialogStyle regression tests. Aligned APK was
installed on Onyx after the user requested alignment. OPD connection was
refused; instrumentation execution and runtime visual comparison remain
unperformed. Building instrumentation sources does not establish device-level
visual acceptance.
