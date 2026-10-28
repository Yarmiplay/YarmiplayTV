/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.HeadsetOff: ImageVector
    get() {
        if (_headsetOff != null) {
            return _headsetOff!!
        }
        _headsetOff = materialIcon(name = "Filled.HeadsetOff") {
            materialPath {
                moveTo(12.0f, 4.0f)
                curveTo(15.87f, 4.0f, 19.0f, 7.13f, 19.0f, 11.0f)
                verticalLineTo(13.0f)
                horizontalLineTo(16.08f)
                lineTo(21.0f, 17.92f)
                verticalLineTo(11.0f)
                curveTo(21.0f, 6.03f, 16.97f, 2.0f, 12.0f, 2.0f)
                curveTo(10.05f, 2.0f, 8.24f, 2.62f, 6.77f, 3.68f)
                lineTo(8.21f, 5.12f)
                curveTo(9.3f, 4.41f, 10.6f, 4.0f, 12.0f, 4.0f)
                close()
                moveTo(2.27f, 1.72f)
                lineTo(1.0f, 3.0f)
                lineTo(4.33f, 6.32f)
                curveTo(3.49f, 7.68f, 3.0f, 9.29f, 3.0f, 11.0f)
                verticalLineTo(18.0f)
                curveTo(3.0f, 19.66f, 4.34f, 21.0f, 6.0f, 21.0f)
                horizontalLineTo(9.0f)
                verticalLineTo(13.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(11.0f)
                curveTo(5.0f, 9.83f, 5.29f, 8.74f, 5.79f, 7.78f)
                lineTo(15.0f, 17.0f)
                verticalLineTo(21.0f)
                horizontalLineTo(18.0f)
                curveTo(18.3f, 21.0f, 18.59f, 20.94f, 18.86f, 20.86f)
                lineTo(21.0f, 23.0f)
                lineTo(22.27f, 21.73f)
                lineTo(2.27f, 1.72f)
                close()
            }
        }
        return _headsetOff!!
    }

private var _headsetOff: ImageVector? = null
