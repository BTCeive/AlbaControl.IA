package com.albacontrol.utils

object LegacyUtil {
  fun safeString(s: Any?): String = s?.toString() ?: ""
}
