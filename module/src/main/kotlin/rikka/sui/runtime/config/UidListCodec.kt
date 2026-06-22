package rikka.sui.runtime.config

object UidListCodec {
    @JvmStatic
    fun encode(uids: IntArray): String {
        return uids.joinToString(separator = "\n", postfix = "\n")
    }

    @JvmStatic
    fun decode(text: String): IntArray {
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .sorted()
            .toList()
            .toIntArray()
    }
}
