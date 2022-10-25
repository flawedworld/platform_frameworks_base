/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import android.telephony.Annotation.NetworkType
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy

/**
 * A SysUI type to represent the [NetworkType] that we pull out of [TelephonyDisplayInfo]. Depending
 * on whether or not the display info contains an override type, we may have to call different
 * methods on [MobileMappingsProxy] to generate an icon lookup key.
 */
sealed interface ResolvedNetworkType {
    @NetworkType val type: Int
}

data class DefaultNetworkType(@NetworkType override val type: Int) : ResolvedNetworkType

data class OverrideNetworkType(@NetworkType override val type: Int) : ResolvedNetworkType
