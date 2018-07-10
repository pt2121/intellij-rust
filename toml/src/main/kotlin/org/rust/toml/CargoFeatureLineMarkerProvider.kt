/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.workspace.CargoWorkspace.FeatureState
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.icons.RsIcons
import org.rust.lang.core.psi.ext.findCargoPackage
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
            val cargoPackage = file.findCargoPackage() ?: continue
            val features = cargoPackage.features.associate { it.name to it.state }
            result += annotateTable(el, features, file.project, cargoPackage.origin)
        }
    }

    private fun annotateTable(
        el: TomlTable,
        features: Map<String, FeatureState>,
        project: Project,
        packageOrigin: PackageOrigin
    ): Collection<LineMarkerInfo<PsiElement>> {
        val names = el.header.names
        val lastName = names.lastOrNull() ?: return emptyList()
        if (!lastName.isFeaturesKey) return emptyList()

        project.rustSettings.modify { state ->
            state.packagesSettings = state.packagesSettings.copy(
                cargoFeaturesAdditional = state.packagesSettings.cargoFeaturesAdditional.toMutableMap().apply {
                    for (feature in el.entries.map { it.name }) {
                        this.putIfAbsent(feature, false)
                    }
                }
            )
        }

        return el.entries.mapNotNull {
            val featureName = it.name
            genLineMarkerInfo(it.key, featureName, features[featureName], project, packageOrigin)
        }
    }

    private fun genLineMarkerInfo(
        element: TomlKey,
        name: String,
        featureState: FeatureState?,
        project: Project,
        packageOrigin: PackageOrigin
    ): LineMarkerInfo<PsiElement>? {
        val anchor = element.bareKey
        val icon = when (featureState) {
            FeatureState.Enabled -> RsIcons.FEATURE_CHECKED_MARK
            FeatureState.Disabled -> RsIcons.FEATURE_UNCHECKED_MARK
            null -> RsIcons.FEATURE_UNCHECKED_MARK
        }

        val toggleFeature = {
            project.rustSettings.modify {
                val oldValue = it.packagesSettings.cargoFeaturesAdditional.getOrDefault(name, false)
                val newFeatures = it.packagesSettings.cargoFeaturesAdditional.toMutableMap().apply {
                    this[name] = !oldValue
                }
                it.packagesSettings = it.packagesSettings.copy(cargoFeaturesAdditional = newFeatures)
            }
        }

        return if (packageOrigin == PackageOrigin.WORKSPACE) {
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
