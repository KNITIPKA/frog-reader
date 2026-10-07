# Paper page turn

In the reader settings, choose **Pages → Page curl**. The saved `PAGE_CURL`
identifier now selects the paper renderer. Existing animation preferences keep
their values; Slide remains available and Cascade still uses the slide renderer.

The sheet bends over a cylinder in 3D, with perspective, a finger-controlled
diagonal fold, a shaded reverse face and a cast shadow. Swipes control the fold
continuously and can be cancelled. Edge taps, volume keys and selection's edge
auto-advance use the same renderer. RTL publications mirror the physical fold
independently of the app language. The completion screen remains a pager page.

The exact initial touch height selects the grabbed edge point and the centre
of perspective, including touches inside the text. There are no top/middle/bottom
zones snapping a text-height gesture to a page corner. Both pointer coordinates
drive the grabbed point, with the perspective projection accounted for. A
two-dimensional drag owns one page pair and one direction until release and
settling finish. It can start with a vertical corner pull, then reverse or
leave the viewport without navigating across other pages. Pager's horizontal
pointer scrolling is disabled only for Page curl; taps, selection, brightness,
pinch, accessibility actions and programmatic navigation retain their handlers.
After release, the last grip interpolates directly to completion or cancellation
without adding a second tap trajectory. The complete bound edge remains fixed:
left for LTR books, right for RTL books. An unreachable diagonal grip meets
that physical constraint: project the grip into its reachable circle and limit
the bend radius before solving the crease. Moving a corner upward continues
upward at this limit rather than translating the whole crease back down. Backwards
turns unroll the previous sheet from the same spine. Each sheet includes its own
page/chapter footer in the cached texture, including the reverse face; the
adjacent page retains its own number rather than sharing a stationary overlay.

At the reach limit, reserve room for a thin cylinder so tiny pointer motion
cannot toggle a visible fold between zero and nonzero radius. The reverse flat
face uses the same ink and shade at every radius, and a sharp visible fold keeps
its contact shadow. During settling to the flat sheet, also straighten the
crease continuously. An interior-height grab otherwise leaves a finite corner
beyond its diagonal crease until the renderer's endpoint reset.

Taps and volume-key turns take 480 ms with a quintic curve that starts and ends
at zero velocity and acceleration. Settling after UP uses a separate continuation:
project both finger-velocity components onto the two-dimensional drag's progress,
retain velocity towards the selected endpoint, and brake to rest without
overshooting. A stopped finger or reversal starts gently. Distance and release
speed select a 160–480 ms duration; tiny already-flat corrections finish directly.
The initial slope is bounded so even extreme flicks cannot reverse the animation
or start another sheet. Compose still supplies frame timing and animator scale.

For an interior-height grab, engage diagonal tilt gradually over the first 0.08
of physical sheet progress. This removes the finite triangular fold that otherwise
appears immediately when a vertical movement crosses touch slop. The exact DOWN
height remains the anchor, and established drags follow the pointer without a
temporal filter. The thickness reserved at the spine limit also grows smoothly
rather than introducing a sharp change as the cylinder reaches its contact radius.

## Rendering and resource bounds

- Keep the existing Compose `PagerState` as the navigation owner. A finger drag
  leaves Pager at its origin, paints the neighbouring pair in place and updates
  Pager exactly once after the final commit/cancel frame. Persisted progress and
  completion still come from `settledPage`. Live progress follows the gesture,
  and chapter feedback uses the target chosen at release.
- Record page content into a managed `GraphicsLayer`. An inner graphics layer
  isolates content recording from the outer animation's draw invalidations.
  Text measurement, composable layout and page recording do not run just
  because the fold advances.
- Cache the page texture with `CompositingStrategy.Offscreen`. There is no CPU
  bitmap capture, GPU readback, per-frame texture upload or separate GL surface.
  Pager's existing one-page neighbour window bounds layer ownership; disposal
  releases each managed layer when its page leaves composition.
- Divide only the cylindrical bend into 40 strips. Each flat section takes a
  single projective map, giving at most 42 strips, with offscreen strips culled.
  Tilt the cylinder's coordinates rather than subdividing a full 2D mesh.
- Reuse geometry arrays, matrices, paths, paints and the shadow gradient.
  Cache the strip normals, reuse curvature/perspective for both corners of each
  strip edge, and stop radius solving as soon as its bound stops changing.
  Draw shades directly over each mapped texture. No per-strip `saveLayer`,
  dynamic blur, `RenderEffect` or API 28+ shadow call is needed on Android 8.
- Read gesture progress in drawing and layer callbacks. The half-page change
  of `currentPage` does not change the logical sheet or its continuous progress.
  Standard Compose animation timing respects the system animator duration scale.
- Latch the owned sheet pair and direction in `PageCurlGesture`. Freeze the
  grip on UP and animate from that same geometry; a finger gesture never starts
  Pager's fling. A cancelled/pinch gesture returns to its origin, and returning
  to the outer edge cannot turn the next or previous sheet. Resetting for a seek
  cancels the outstanding settle job so it cannot navigate later. Programmatic
  Pager animations retain `PageCurlSession` and its endpoint overshoot guard.
- Restore page-left/right, scroll-by and scroll-to-index accessibility actions
  explicitly when disabling Pager's pointer scroll.

## Verification

`PageCurlGeometryTest` checks flat endpoints, perspective and the reverse face,
finger tracking at all four corners, vertical motion, release, top/bottom and
RTL symmetry, text-height grabs (including both sides of the former zone
boundaries), strip continuity, cancellation, buffer reuse, page-index changes,
the complete fixed spine, book edges and finite coordinates at narrow, landscape
and large viewport sizes. `PageCurlSessionTest` replays release's idle gap on
both sides of the half-page swap, checks identical held/released vertices,
continued progress, cancellation and adjacent-page overshoot suppression. The
corner-up-then-right regression checks reachable grip coordinates, monotonic
vertical motion, a fixed pair while held, identical held/released vertices,
commit/cancel, book boundaries and interruption by programmatic navigation.
Contact-limit jitter checks positive curvature and shadow, stable radius and
nonsingular source/projected strips. Interior-height settling checks that the
remaining corner is below one pixel before the final frame, while the complete
spine remains fixed through unfolding in portrait, narrow and landscape sizes.
`PageCurlMotionTest` samples Compose's actual animation values at 60/120 Hz,
checks gradual tap/resting-release starts, retention of a moving finger's speed
in both directions, bounded monotone commit/cancel paths even at extreme speeds,
gentle terminal braking and the horizontal/vertical release-speed projection.
The geometry tests also bound the initial corner displacement when a small
vertical movement inside the page first crosses touch slop.
The CLI build verifies integration, not the phone's graphics driver or frame rate.

Owner phone checks: slow drags from upper/middle/lower text heights and the actual
top/bottom corners, bottom-right pull upward then back to the right without UP,
release before and after the halfway point, fast reversals,
held corner near its reach limit, final cancellation frames from an interior
text-height grab,
forward/back edge taps, volume keys,
the page/chapter footer moving with its sheet and showing the neighbour's number,
chapter boundaries, last page and completion, Light/Beige/OLED, RTL books, images,
footnotes, selection auto-advance, oversized tables, rotation and animator scale 0.
