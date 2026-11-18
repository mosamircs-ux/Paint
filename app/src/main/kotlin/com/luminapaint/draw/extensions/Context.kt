package com.luminapaint.draw.extensions

import android.content.Context
import com.luminapaint.draw.helpers.Config

val Context.config: Config get() = Config.newInstance(applicationContext)
