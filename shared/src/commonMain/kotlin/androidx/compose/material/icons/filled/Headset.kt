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

public val Icons.Filled.Headset: ImageVector
    get() {
        if (_headset != null) {
            return _headset!!
        }
        _headset = materialIcon(name = "Filled.Headset") {
            materialPath {
                moveTo(12.0f, 1.0f)
                curveTo(7.03f, 1.0f, 3.0f, 5.03f, 3.0f, 10.0f)
                verticalLineTo(17.0f)
                curveTo(3.0f, 18.66f, 4.34f, 20.0f, 6.0f, 20.0f)
                horizontalLineTo(9.0f)
                verticalLineTo(12.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(10.0f)
                curveTo(5.0f, 6.13f, 8.13f, 3.0f, 12.0f, 3.0f)
                curveTo(15.87f, 3.0f, 19.0f, 6.13f, 19.0f, 10.0f)
                verticalLineTo(12.0f)
                horizontalLineTo(15.0f)
                verticalLineTo(20.0f)
                horizontalLineTo(18.0f)
                curveTo(19.66f, 20.0f, 21.0f, 18.66f, 21.0f, 17.0f)
                verticalLineTo(10.0f)
                curveTo(21.0f, 5.03f, 16.97f, 1.0f, 12.0f, 1.0f)
                close()
            }
        }
        return _headset!!
    }

private var _headset: ImageVector? = null
