/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment
import com.intellij.psi.PsiElement
import org.rust.cargo.project.model.impl.CargoProjectImpl
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.FeatureState
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.icons.RsIcons
import org.rust.lang.core.psi.ext.findCargoPackage
import org.rust.lang.core.psi.ext.findCargoProject
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

class CargoFeatureLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<PsiElement>, result: MutableCollection<LineMarkerInfo<PsiElement>>) {
        if (!tomlPluginIsAbiCompatible()) return
        for (el in elements) {
            val file = el.containingFile as? TomlFile ?: continue
            if (file.name.toLowerCase() != "cargo.toml") continue
            if (el !is TomlTable) continue
            val cargoProject = file.findCargoProject() as? CargoProjectImpl ?: continue
            val cargoPackage = file.findCargoPackage() ?: continue
            val features = cargoPackage.features.state
            result += annotateTable(el, features, cargoProject, cargoPackage)
        }
    }

    private fun annotateTable(
        el: TomlTable,
        features: Map<String, FeatureState>,
        cargoProject: CargoProjectImpl,
        cargoPackage: CargoWorkspace.Package
    ): Collection<LineMarkerInfo<PsiElement>> {
        val names = el.header.names
        val lastName = names.lastOrNull() ?: return emptyList()
        if (!lastName.isFeaturesKey) return emptyList()

        return el.entries.mapNotNull {
            val featureName = it.name
            genLineMarkerInfo(
                it.key,
                featureName,
                features[featureName],
                cargoProject,
                cargoPackage)
        }
    }

    private fun genLineMarkerInfo(
        element: TomlKey,
        name: String,
        featureState: FeatureState?,
        cargoProject: CargoProjectImpl,
        cargoPackage: CargoWorkspace.Package
    ): LineMarkerInfo<PsiElement>? {
        val anchor = element.bareKey
        val icon = when (featureState) {
            FeatureState.Enabled -> RsIcons.FEATURE_CHECKED_MARK
            FeatureState.Disabled -> RsIcons.FEATURE_UNCHECKED_MARK
            null -> RsIcons.FEATURE_UNCHECKED_MARK
        }

        val toggleFeature = {
            val oldValue = cargoPackage.features.state.getOrDefault(name, FeatureState.Disabled).toBoolean()
            runWriteAction {
                cargoProject.userOverriddenFeatures[name] = !oldValue
            }
            cargoPackage.syncFeatures(cargoProject.userOverriddenFeatures)
        }

        return if (cargoPackage.origin == PackageOrigin.WORKSPACE) {
            LineMarkerInfo(
                anchor,
                anchor.textRange,
                icon,
                { "Enable feature `$name`" },
                { _, _ -> toggleFeature() },
                Alignment.LEFT)
        } else {
            LineMarkerInfo(anchor, anchor.textRange, icon, null, null, Alignment.LEFT)
        }
    }
}

private val TomlKey.bareKey get() = firstChild
private val TomlKeyValue.name get() = key.text
