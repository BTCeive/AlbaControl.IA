package com.example.albacontrol.learning

import org.json.JSONObject

data class CorrectionField(
    val fieldName: String,
    val correctedValue: String,
    val coords: Map<String, Int>
)

object CorrectionPayloadBuilder {
    fun build(provider: String, nif: String, fields: List<CorrectionField>): JSONObject {
        val root = JSONObject()
        root.put("provider", provider)
        root.put("nif", nif)

        val arr = org.json.JSONArray()
        fields.forEach {
            val obj = JSONObject()
            obj.put("field", it.fieldName)
            obj.put("value", it.correctedValue)
            obj.put("coords", JSONObject(it.coords))
            arr.put(obj)
        }

        root.put("fields", arr)
        return root
    }
}
