package dev.screenveil.app.model

import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallerHostTest {
    private fun approved(url: String) = ModelInstaller.isApprovedModelUrl(URL(url))

    @Test fun catalogDownloadUrlsAreApproved() {
        SpecializedModelCatalog.all.forEach { assertTrue(it.downloadUrl, approved(it.downloadUrl)) }
    }

    @Test fun knownHostingRedirectTargetsAreApproved() {
        assertTrue(approved("https://cdn-lfs.hf.co/repos/aa/bb/file"))
        assertTrue(approved("https://release-assets.githubusercontent.com/github-production-release-asset/1"))
        assertTrue(approved("https://objects.githubusercontent.com/x"))
    }

    @Test fun userContentAndLookalikeHostsAreRejected() {
        assertFalse(approved("https://raw.githubusercontent.com/user/repo/main/model.onnx"))
        assertFalse(approved("https://user.github.com/x"))
        assertFalse(approved("https://evilhf.co/x"))
        assertFalse(approved("https://hf.co.evil.example/x"))
        assertFalse(approved("http://huggingface.co/x"))
        assertFalse(approved("https://huggingface.co:8443/x"))
        assertFalse(approved("https://user:pw@huggingface.co/x"))
    }
}
