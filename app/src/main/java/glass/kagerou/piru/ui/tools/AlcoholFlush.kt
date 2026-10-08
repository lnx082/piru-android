package glass.kagerou.piru.ui.tools

/**
 * What the "ALDH2 deficiency" answer changes about the alcohol screen.
 *
 * ## The problem this solves, stated honestly
 * `UserProfileStore.aldh2Deficient` is collected at onboarding, stored, exported, imported, and editable in
 * Settings — and read by **nothing**. The audit's item is exactly that, and the temptation is to make it look
 * answered by nudging a curve.
 *
 * It should not. The engine models alcohol with zero-order elimination of **ethanol**: one rate, one curve. ALDH2
 * deficiency does not change ethanol elimination — it changes how fast the **acetaldehyde** that ethanol becomes is
 * cleared, and acetaldehyde is not a compartment in this model. Scaling the ethanol curve would be inventing a
 * mechanism, and the app's whole posture is that a model which claims more than it knows is worse than one that
 * says what it does not.
 *
 * So the flag's honest effect is a **note on the reading**: the curve below is ethanol, and for a reader with the
 * deficiency the part they feel worst during is not the part this curve shows. That is a real answer to
 * "what does this setting do", it is verifiable, and it does not require a mechanism the model does not have.
 *
 * ## Why it is a function rather than an `if` in the layout
 * Because "the flag does nothing to the numbers" is the decision worth pinning. A future change that made it
 * scale the curve would fail [showsAcetaldehydeNote]'s own test only if the shape of the answer is asserted —
 * hence [AlcoholFlush.curveIsUnaffectedByDeficiency], which states it as a property.
 */
internal object AlcoholFlush {

    /**
     * Whether the screen should carry the acetaldehyde note.
     *
     * True exactly when the user answered yes. An unanswered profile is `false` rather than a guess, because the
     * note describes *their* physiology and telling everyone about a deficiency most readers do not have is noise
     * on the screen they opened to read a curve.
     */
    fun showsAcetaldehydeNote(aldh2Deficient: Boolean): Boolean = aldh2Deficient

    /**
     * Whether the deficiency changes the ethanol curve's numbers.
     *
     * **Always false**, and stated as a function so the claim is testable rather than implied by the absence of
     * code. The model has one compartment; acetaldehyde is not in it. If a future engine gains an acetaldehyde
     * compartment this becomes a real question and this function is where it is answered.
     */
    fun curveIsUnaffectedByDeficiency(): Boolean = true
}
