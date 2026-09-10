package id.web.izs.sshclient.core.term

/**
 * Grid renderer draws each row as ONE paragraph layout at natural glyph
 * advances, so text only lands on the grid when every glyph is exactly one
 * cell wide. System "monospace" is not guaranteed monospace: OEM custom
 * fonts can replace it phone-wide with a proportional face (narrow `i`,
 * wide `W`, short space). Then rows render narrower than `cols * charW`:
 * text ends early with dead space on the right while the server wrap column
 * is correct — looks like a resize failure, but the pty size is fine.
 *
 * Pure decision helper (measured per-char advances in, verdict out) so the
 * composable stays thin and this stays JVM-testable.
 *
 * @param digitAdvance per-char advance of the reference run (`"0000" / 4`).
 * @param samples per-char advances of narrow / wide / space runs.
 * @param tolerance max relative deviation from the digit advance.
 * @return false when the face is measurably proportional; true also when
 *   anything measures 0 (fonts not loaded yet — abstain, never condemn).
 */
fun isMonospaceSample(
    digitAdvance: Float,
    samples: List<Float>,
    tolerance: Float = 0.10f,
): Boolean {
    if (digitAdvance <= 0f || samples.any { it <= 0f }) return true
    return samples.all {
        kotlin.math.abs(it - digitAdvance) / digitAdvance <= tolerance
    }
}
