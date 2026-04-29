package com.lumi.app.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer

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

    /** Load ALL contacts with phone numbers (used for diacritic-tolerant search). */
    private fun getAllContacts(): List<Contact> = searchByName("")

    /** Find best match: alias → exact → diacritic-normalized → first-name only. */
    fun findBestMatch(name: String): Contact? {
        resolveAlias(name)?.let { return it }
        searchByName(name).firstOrNull()?.let { return it }

        // Diacritic-normalized fallback (handles Romanian ă/â/î/ș/ț)
        val normQuery = normalize(name)
        getAllContacts().firstOrNull { normalize(it.name).contains(normQuery) }?.let { return it }

        // First-name only fallback
        val firstName = name.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        if (firstName.length >= 3) {
            searchByName(firstName).firstOrNull()?.let { return it }
            getAllContacts().firstOrNull { normalize(it.name).contains(normalize(firstName)) }?.let { return it }
        }
        return null
    }

    /**
     * Fuzzy resolve a name alias like "mama", "mom", "tata", "dad" to a contact.
     */
    fun resolveAlias(alias: String): Contact? {
        val aliasMap = mapOf(
            "mama" to listOf("mama", "mom", "mother", "mami", "mamica"),
            "tata" to listOf("tata", "dad", "father", "papa", "tatic"),
            "bunica" to listOf("bunica", "grandma", "grandmother", "bunica"),
            "bunicul" to listOf("bunicul", "grandpa", "grandfather"),
            "sotie" to listOf("sotie", "soție", "nevasta", "nevastă", "wife"),
            "sot" to listOf("sot", "soț", "barbat", "bărbat", "husband")
        )

        val lowerAlias = normalize(alias)
        val searchTerms = aliasMap.entries
            .firstOrNull { (_, variants) -> variants.any { normalize(it) == lowerAlias } }
            ?.value ?: listOf(alias)

        for (term in searchTerms) {
            val results = searchByName(term)
            if (results.isNotEmpty()) return results.first()
        }
        return null
    }

    /** Add a new contact with given name and phone number. Returns true on success. */
    fun addContact(name: String, phone: String): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        val rawContactIdx = ops.size
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIdx)
            .withValue(ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())
        if (phone.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIdx)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
        }
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (_: Exception) { false }
    }

    /** Add a phone number to an existing contact. Returns true on success. */
    fun addPhoneToContact(contactId: Long, phone: String): Boolean {
        val rawContactId = getRawContactId(contactId) ?: return false
        val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            .withValue(ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build()
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op))
            true
        } catch (_: Exception) { false }
    }

    private fun getRawContactId(contactId: Long): Long? {
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
        return context.contentResolver.query(uri, projection, selection, arrayOf(contactId.toString()), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun formatForGemini(contacts: List<Contact>): String {
        if (contacts.isEmpty()) return "Niciun contact gasit."
        return contacts.joinToString("\n") { c ->
            "${c.name}: ${c.phoneNumbers.joinToString(", ")}"
        }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("[^\\p{ASCII}]"), "")
            .lowercase()
            .trim()
}
