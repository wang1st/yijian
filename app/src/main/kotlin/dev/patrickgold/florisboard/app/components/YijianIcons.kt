/*
 * Copyright (C) 2026 The yijian Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons drawn here rather than taken from the Material set.
 *
 * The bottom bar is the one place where an icon has to say exactly what its page does. A star
 * says "favourite", not "learning report"; a bar chart says "learning report". Drawing it keeps
 * the meaning exact, and keeps every icon in the bar at the same optical weight.
 *
 * Uses the same `materialIcon { materialPath { … } }` builder the icons in `lib/compose` use, so
 * it behaves identically to the stock ones under tinting and scaling.
 */
object YijianIcons {

    /**
     * Three ascending bars on a baseline — the learning report.
     *
     * Path data is on the 24x24 grid Material icons use, so it sits at the same optical size as
     * the icons beside it in the navigation bar.
     */
    val BarChart: ImageVector by lazy {
        materialIcon(name = "Outlined.BarChart") {
            // Baseline
            materialPath {
                moveTo(3.0f, 20.4f)
                lineTo(21.0f, 20.4f)
                lineTo(21.0f, 22.2f)
                lineTo(3.0f, 22.2f)
                close()
            }
            // Short bar
            materialPath {
                moveTo(4.6f, 13.0f)
                lineTo(8.0f, 13.0f)
                lineTo(8.0f, 19.2f)
                lineTo(4.6f, 19.2f)
                close()
            }
            // Medium bar
            materialPath {
                moveTo(10.3f, 9.0f)
                lineTo(13.7f, 9.0f)
                lineTo(13.7f, 19.2f)
                lineTo(10.3f, 19.2f)
                close()
            }
            // Tall bar
            materialPath {
                moveTo(16.0f, 4.0f)
                lineTo(19.4f, 4.0f)
                lineTo(19.4f, 19.2f)
                lineTo(16.0f, 19.2f)
                close()
            }
        }
    }
}
