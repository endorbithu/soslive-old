package info.soslive.stream.drive

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GoogleDriveApiTest {

    private val server = MockWebServer()
    private val tokenCalls = mutableListOf<Boolean>()
    private lateinit var api: GoogleDriveApi

    @Before
    fun setUp() {
        server.start()
        api = GoogleDriveApi(OkHttpClient(), { force -> tokenCalls += force; if (force) "fresh" else "cached" }, server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `finds files by appProperties tag, oldest first, with the bearer token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"f1","name":"SOSlive","createdTime":"2025-01-01T00:00:00Z"}]}"""))
        val files = api.findByTag(DriveTags.ROOT, folderOnly = true)
        assertEquals("f1", files.single().id)

        val request = server.takeRequest()
        assertEquals("Bearer cached", request.getHeader("Authorization"))
        assertEquals("/drive/v3/files", request.requestUrl!!.encodedPath)
        val q = request.requestUrl!!.queryParameter("q")!!
        assertTrue(q, q.contains("appProperties has { key='soslive' and value='root' }"))
        assertTrue(q, q.contains("trashed=false"))
        assertTrue(q, q.contains("mimeType='application/vnd.google-apps.folder'"))
        assertEquals("createdTime", request.requestUrl!!.queryParameter("orderBy"))
    }

    @Test
    fun `401 refreshes the token once and retries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid Credentials"}}"""))
        server.enqueue(MockResponse().setBody("""{"id":"x","name":"n"}"""))
        assertEquals("x", api.getFile("x")!!.id)
        assertEquals(listOf(false, true), tokenCalls)
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `404 maps to DriveNotFoundException and getFile returns null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"File not found"}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            api.download("gone")
            fail()
        } catch (e: DriveNotFoundException) {
            assertTrue(e.message!!.contains("File not found"))
        }
        assertEquals(null, api.getFile("gone"))
    }

    @Test
    fun `createFile uploads metadata and content as multipart related`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"e1","name":"2026-09-29 14:03:22.json"}"""))
        api.createFile("2026-09-29 14:03:22.json", JSON_MIME, "folder1", DriveTags.EVENT, """{"v":1}""".toByteArray())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files", request.requestUrl!!.encodedPath)
        assertEquals("multipart", request.requestUrl!!.queryParameter("uploadType"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/related"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains(""""parents":["folder1"]"""))
        assertTrue(body, body.contains(""""appProperties":{"soslive":"event"}"""))
        assertTrue(body, body.contains("""{"v":1}"""))
    }

    @Test
    fun `update, share and trash use the documented endpoints`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"id":"e1"}""")) }
        api.updateContent("e1", JSON_MIME, "{}".toByteArray())
        api.shareAnyoneReader("e1")
        api.trash("e1")

        val update = server.takeRequest()
        assertEquals("PATCH", update.method)
        assertEquals("/upload/drive/v3/files/e1", update.requestUrl!!.encodedPath)
        assertEquals("media", update.requestUrl!!.queryParameter("uploadType"))

        val share = server.takeRequest()
        assertEquals("/drive/v3/files/e1/permissions", share.requestUrl!!.encodedPath)
        assertEquals("""{"type":"anyone","role":"reader"}""", share.body.readUtf8())

        val trash = server.takeRequest()
        assertEquals("PATCH", trash.method)
        assertEquals("""{"trashed":true}""", trash.body.readUtf8())
    }

    @Test
    fun `sharing endpoints - subfolder, move, list, add and remove people`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"ev","name":"events"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"e1"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"permissions":[
                    {"id":"o","type":"user","role":"owner","emailAddress":"me@example.com"},
                    {"id":"a","type":"anyone","role":"reader"},
                    {"id":"p1","type":"user","role":"reader","emailAddress":"anna@example.com","displayName":"Anna"}
                ]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"id":"p2","emailAddress":"bela@example.com"}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        api.createFolder(EVENTS_FOLDER_NAME, DriveTags.EVENTS, "root1")
        api.moveFile("e1", "root1", "ev")
        val people = api.listUserPermissions("ev")
        val added = api.shareWithUser("ev", "bela@example.com")
        api.removePermission("ev", "p1")

        val folder = server.takeRequest().body.readUtf8()
        assertTrue(folder, folder.contains(""""parents":["root1"]"""))
        assertTrue(folder, folder.contains(""""appProperties":{"soslive":"events"}"""))

        val move = server.takeRequest()
        assertEquals("PATCH", move.method)
        assertEquals("ev", move.requestUrl!!.queryParameter("addParents"))
        assertEquals("root1", move.requestUrl!!.queryParameter("removeParents"))

        assertEquals(listOf(DrivePermission("p1", "anna@example.com", "Anna")), people)
        assertEquals("/drive/v3/files/ev/permissions", server.takeRequest().requestUrl!!.encodedPath)

        val share = server.takeRequest()
        assertEquals("true", share.requestUrl!!.queryParameter("sendNotificationEmail"))
        assertEquals("""{"type":"user","role":"reader","emailAddress":"bela@example.com"}""", share.body.readUtf8())
        assertEquals(DrivePermission("p2", "bela@example.com"), added)

        val remove = server.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("/drive/v3/files/ev/permissions/p1", remove.requestUrl!!.encodedPath)
    }

    @Test
    fun `retryable classification`() {
        assertTrue(DriveException(429, "x").retryable)
        assertTrue(DriveException(503, "x").retryable)
        assertTrue(DriveException(403, "Rate Limit Exceeded / userRateLimitExceeded").retryable)
        assertFalse(DriveException(403, "insufficientFilePermissions").retryable)
        assertFalse(DriveException(400, "bad").retryable)
    }
}
