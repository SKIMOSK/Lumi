package com.lumi.app.contacts

import android.content.Context
import android.provider.ContactsContract

data class Contact(
    val id: Long,
    val name: String,
    val phoneNumbers: List<String>,
    val emails: List<String>
)

class ContactsHelper(private val context: Context) {

    /** Search contacts by name substring. Case-insensitive. */
    fun searchByName(query: String): List<Contact> {
        val contacts = mutableMapOf<Long, Contact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$query%")

        context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numIdx) ?: continue
                val existing = contacts[id]
                if (existing != null) {
                    contacts[id] = existing.copy(phoneNumbers = existing.phoneNumbers + number)
                } else {
                    contacts[id] = Contact(id, name, listOf(number), emptyList())
                }
            }
        }
        return contacts.values.toList()
    }

    /**
     * Fuzzy resolve a name alias like "mama", "mom", "tata", "dad" to a contact.
     * Returns the best match or null.
     */
    fun resolveAlias(alias: String): Contact? {
        val aliasMap = mapOf(
            "mama" to listOf("mama", "mom", "mother", "mami", "mãmica", "mamica"),
            "tata" to listOf("tata", "dad", "father", "papa", "tătic", "tatic"),
            "bunica" to listOf("bunica", "grandma", "grandmother", "bunică"),
            "bunicul" to listOf("bunicul", "grandpa", "grandfather"),
            "soție" to listOf("soție", "nevastă", "wife", "sotie", "nevasta"),
            "soț" to listOf("soț", "bărbat", "husband", "sot", "barbat")
        )

        val lowerAlias = alias.lowercase().trim()
        val searchTerms = aliasMap.entries
            .firstOrNull { (_, variants) -> variants.any { it == lowerAlias } }
            ?.value ?: listOf(lowerAlias)

        for (term in searchTerms) {
            val results = searchByName(term)
            if (results.isNotEmpty()) return results.first()
        }
        return null
    }

    fun formatForGemini(contacts: List<Contact>): String {
        if (contacts.isEmpty()) return "Niciun contact găsit."
        return contacts.joinToString("\n") { c ->
            "${c.name}: ${c.phoneNumbers.joinToString(", ")}"
        }
    }
}
