package tools

class ToolRegistry(tools: List<Tool>) {
    private val map = tools.associateBy { it.name }

    fun specs() = map.values.map { it.spec() }
    fun get(name: String) = map[name]
}
