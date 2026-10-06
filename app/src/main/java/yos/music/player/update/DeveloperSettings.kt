package yos.music.player.update

import com.tencent.mmkv.MMKV

/** Developer-mode toggles, independent from user-facing settings. */
object DeveloperSettings {
    private val store by lazy { MMKV.mmkvWithID("sonify_developer") }
    var fakeUpdateDialog: Boolean
        get() = store.decodeBool("fake_update_dialog", false)
        set(value) { check(store.encode("fake_update_dialog", value)) }
}
