/*
 * Copyright (C) 2013-2026 Federico Iosue (omninotes@iosue.it)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.feio.android.omninotes.utils

import android.net.Uri
import android.util.Base64
import it.feio.android.omninotes.exceptions.checked.ContentSecurityException
import it.feio.android.omninotes.helpers.LogDelegate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec
import kotlin.jvm.Throws


/**
 * 安全工具类：集中提供加密、解密、MD5摘要与路径校验能力。
 * @why 私有构造禁止外部实例化，全部能力以静态方法（companion object）暴露，
 *       供 DbHelper 等数据层与存储块无状态调用，避免重复实现加解密逻辑。
 */
class Security private constructor() {

    //伴生对象：Kotlin 中承载“静态成员”的等价物，方法通过 Security.ma5() 形式调用
    companion object {
        /**
         * 计算字符串的 MD5 摘要，返回32位十六进制小写字符串。
         * @why 用于笔记锁定密码等场景的哈希比对：只存摘要不存明文，密码本身可校验但不可逆推。
         * @return 摘要字符串；算法不可用时返回空串（不抛异常，保证调用方功能不中断）
         */
        @JvmStatic
        fun md5(s: String): String {
            return try {
                val digest = MessageDigest.getInstance("MD5")//获取 MD5 摘要算法实例（JDK 内置）
                digest.update(s.toByteArray())//把明文字节序列喂入摘要器
                val messageDigest = digest.digest()//完成计算，得到16字节原始摘要

                // Creates Hex String（生成十六进制字符串）
                val hexString = StringBuilder()
                for (b in messageDigest) {
                    hexString.append(Integer.toHexString(0xFF and b.toInt()))//0xFF 掩码去掉符号位：byte 有符号，掩码后转无符号整数再转十六进制
                }
                hexString.toString()
            } catch (e: NoSuchAlgorithmException) {
                //算法不存在属环境异常：记日志并返回空串，不让密码流程崩溃
                LogDelegate.w("Something is gone wrong calculating MD5", e)
                ""
            }
        }

        /**
         * 使用口令对字符串加密，输出 Base64 编码的密文。
         * @why 锁定笔记的内容在入库前必须加密，防止明文直接落盘（对应DbHelper.updateNote 中的调用）；
         *       返回 Base64 字符串便于在 SQLite 中以文本列存储。
         * @return 加密后的 Base64 字符串：加密失败时返回原文（降级保可用）
         */
        @JvmStatic
        fun encrypt(value: String, password: String): String? {
            return try {
                val keySpec = DESKeySpec(password.toByteArray(StandardCharsets.UTF_8))//以口令的 UTF-8 字节构造 DES 密钥材料（56位有效密钥）
                val keyFactory = SecretKeyFactory.getInstance("DES")//密钥工厂：把密钥材料规范化为算法可用密钥
                val key = keyFactory.generateSecret(keySpec)
                val clearText = value.toByteArray(StandardCharsets.UTF_8)//明文字节
                // Cipher is not thread safe（Cipher 不是线程安全）
                val cipher = Cipher.getInstance("DES")// 每次调用新建 Cipher：实例非线程安全，避免并发加解密互相污染
                cipher.init(Cipher.ENCRYPT_MODE, key)// 初始化为加密模式
                Base64.encodeToString(cipher.doFinal(clearText), Base64.DEFAULT)// 执行加密后 Base64 编码为可打印文本
            } catch (e: Exception) {
                // 任何加解密异常都降级返回原文：保证笔记功能可用，错误仅记日志
                LogDelegate.e("Something is gone wrong encrypting", e)
                value
            }
        }

        /**
         * 使用口令解密 Base64 密文，还原明文内容（与 encrypt 对称）。
         * @why 读取锁定便签时调用（对应 DbHelper.getNotes 中的解密分支）：密码正确才能还原明文；
         * @return 解密后的明文；解密失败（如口令错误、密文损坏）时返回原值，交由上层提示用户
         */

        @JvmStatic
        fun decrypt(value: String?, password: String): String? {
            return try {
                val keySpec = DESKeySpec(password.toByteArray(StandardCharsets.UTF_8))
                val keyFactory = SecretKeyFactory.getInstance("DES")
                val key = keyFactory.generateSecret(keySpec)
                val encryptedPwdBytes = Base64.decode(value, Base64.DEFAULT)//先把 Base64 文本还原为密文字节
                // cipher is not thread safe（密码器不是线程安全）
                val cipher = Cipher.getInstance("DES")// 同样新建实例，避免共享 Cipher 的并发问题
                cipher.init(Cipher.DECRYPT_MODE, key)//初始化为解密模式
                val decrypedValueBytes = cipher.doFinal(encryptedPwdBytes)//执行解密
                String(decrypedValueBytes)// 字节还原为字符串（默认 UTF-8）
            } catch (e: Exception) {
                // 口令错误或密文异常都会走到这里：返回原值，不抛异常避免界面崩溃
                LogDelegate.e("Error decrypting", e)
                value
            }
        }

        /**
         * 校验文件路径合法性，拒绝越权与路径穿越。
         * @why 外部传入的 URI（如附件路径）若指向内部数据目录或包含 ../ 可逃逸到任意位置，
         *      读取/删除前必须校验，防止越权访问私有数据（安全边界检查）。
         * @throws ContentSecurityException 路径非法时抛出，由调用方捕获处理
         */

        @JvmStatic
        @Throws(ContentSecurityException::class)
        fun validatePath(path: String?) {
            val uri = Uri.parse(path).path?.replace("/+".toRegex(), "/")// 解析 URI 并压缩连续斜杠，归一化后再判断
            if (uri?.startsWith("/data")!! || uri.contains("../")) {// 命中两种非法形态：①指向内部 /data 目录；②含 ../ 可向上越级
                throw ContentSecurityException("Invalid")// 拒绝访问并抛出受检异常
            }
        }

    }
}