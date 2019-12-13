/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import com.intellij.codeInspection.ProblemHighlightType
import org.rust.ide.colors.RsColor
import org.rust.ide.injected.isDoctestInjection
import org.rust.ide.inspections.fixes.RenameFix
import org.rust.ide.utils.isCfgUnknown
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.DeclarationKind
import org.rust.lang.core.types.DeclarationKind.Parameter
import org.rust.lang.core.types.DeclarationKind.Variable
import org.rust.lang.core.types.liveness

class RsLivenessInspection : RsLocalInspectionTool() {
    override fun buildVisitor(holder: RsProblemsHolder, isOnTheFly: Boolean) =
        object : RsVisitor() {
            override fun visitFunction(func: RsFunction) {
                // Disable inside doc tests
                if (func.isDoctestInjection) return

                // Check #[allow(unused)], #[allow(unused_variables)], #[allow(unused_parameters)]
                val implItem = func.ancestorStrict<RsImplItem>()
                if (func.isUnusedAllowed || implItem?.isUnusedAllowed == true) return

                // Don't analyze functions with unresolved macro calls
                if (func.descendantsOfType<RsMacroCall>().any { it.resolveToMacro() == null }) return

                // Don't analyze functions with unresolved struct literals
                if (func.descendantsOfType<RsStructLiteral>().any { it.path.reference.resolve() == null }) return

                val liveness = func.liveness ?: return

                for (deadDeclaration in liveness.deadDeclarations) {
                    val name = deadDeclaration.binding.name ?: continue
                    if (name.startsWith("_")) continue
                    registerUnusedProblem(holder, deadDeclaration.binding, name, deadDeclaration.kind)
                }
            }
        }

    private fun registerUnusedProblem(
        holder: RsProblemsHolder,
        binding: RsPatBinding,
        name: String,
        kind: DeclarationKind
    ) {
        if (!binding.isPhysical) return

        if (binding.isCfgUnknown) return

        // TODO: remove this check when multi-resolve for `RsOrPats` is implemented
        if (binding.ancestorStrict<RsOrPats>() != null) return

        val message = when (kind) {
            Parameter -> "Parameter `$name` is never used"
            Variable -> "Variable `$name` is never used"
        }
        val descriptor = holder.manager.createProblemDescriptor(
            binding,
            message,
            RenameFix(binding, "_$name"),
            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
            holder.isOnTheFly
        )
        descriptor.setTextAttributes(RsColor.DEAD_CODE.textAttributesKey)
        holder.registerProblem(descriptor)
    }

    val RsOuterAttributeOwner.isUnusedAllowed: Boolean
        get() = with(queryAttributes) {
            hasAttributeWithArg("allow", "unused")
                || hasAttributeWithArg("allow", "unused_variables")
                || hasAttributeWithArg("allow", "unused_parameters")
        }
}
