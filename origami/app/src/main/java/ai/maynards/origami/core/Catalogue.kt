package ai.maynards.origami.core

/**
 * What can be made, grouped as a person looks for it. Each item is one of the checked generators with sizes
 * that suit it; [basedOn] says which, so nothing pretends to be a separate engineering design.
 */
object Catalogue {
    data class Item(val id: String, val group: String, val name: String, val spec: OrganizerSpec, val note: String = "") {
        val basedOn: String get() = spec.template.label
    }

    val items = listOf(
        Item("organizer", "Storage", "Desk organizer", OrganizerSpec(300, 160, 180, 3, 2)),
        Item("drawer-insert", "Storage", "Drawer insert", OrganizerSpec(360, 70, 280, 3, 3)),
        Item("toy-box", "Storage", "Toy box", OrganizerSpec(500, 320, 350, 9, 1)),
        Item("monitor-riser", "Tables", "Monitor riser", OrganizerSpec(500, 110, 220, 9, template = Template.TABLE)),
        Item("laptop-stand", "Tables", "Laptop stand", OrganizerSpec(350, 120, 250, 6, template = Template.TABLE)),
        Item("side-table", "Tables", "Side table", OrganizerSpec(400, 400, 380, 12, template = Template.TABLE)),
        Item("step-stool", "Seating", "Step stool", OrganizerSpec(380, 220, 260, 12, template = Template.TABLE),
            note = "Not load-tested: do not stand on it until a built one is tested"),
        Item("bookshelf", "Shelves", "Bookshelf", OrganizerSpec(600, 800, 250, 12, 4, template = Template.BOOKSHELF)),
        Item("desk-shelf", "Shelves", "Desk shelf", OrganizerSpec(500, 350, 200, 9, 2, template = Template.BOOKSHELF)),
        Item("doll-house", "Toys", "Doll-house shelf", OrganizerSpec(320, 420, 160, 6, 3, template = Template.BOOKSHELF)),
    )

    val groups: List<String> get() = items.map { it.group }.distinct()
}
