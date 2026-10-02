package com.houvven.guise.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.houvven.guise.util.android.Randoms
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@Entity
data class Template(
    @PrimaryKey val id: String = Randoms.uuidNoDash(),
    var name: String,
    var description: String? = null,
    var type: Int,
    var configuration: String,
    var createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis(),
    var packageName: String? = null,
    // 自定义分组名；null = 自动按品牌分组。@Transient：不参与序列化
    // （二维码分享/指纹比对均不受分组影响），仅由 Room 持久化。
    @kotlinx.serialization.Transient
    var groupName: String? = null,
) : java.io.Serializable {

    object Type {
        const val COMMON = 0
        const val EXCLUSIVE = 1
    }

    fun serialization(): String {
        return Json.encodeToString(serializer(), this)
    }

    companion object {
        fun deserialization(json: String): Template {
            return Json.decodeFromString(serializer(), json)
        }
    }
}