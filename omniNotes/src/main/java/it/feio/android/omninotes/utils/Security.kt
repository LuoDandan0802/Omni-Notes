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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.jvm.Throws


/**
 * 安全工具类：集中提供加密、解密、摘要与路径校验能力。
 * @why 私有构造禁止外部实例化，全部能力以静态方法（companion object）暴露，
 *       供 DbHelper 等数据层与存储块无状态调用，避免重复实现加解密逻辑。
 *       第 10 周维护：摘要由 MD5 升级为 SHA-256，对称加密由 DES 升级为 AES-256/GCM，
 *       升级前已落盘的旧密文与旧密码摘要保留只读兼容路径，保证已有数据不失效。
 */
class Security private constructor() {

    //伴生对象：Kotlin 中承载“静态成员”的等价物，方法通过 Security.md5() 形式调用
    companion object {
        // 新摘要算法：SHA-256 输出 256 位，抗碰撞能力远强于已被证实存在碰撞攻击的 MD5
        private const val DIGEST_SHA256 = "SHA-256"

        // AES 采用 GCM 认证加密：既规避 ECB 模式下相同明文块生成相同密文块的缺陷，又能校验密文完整性
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_ALGORITHM = "AES"
        // GCM 认证标签长度（单位：位）：解密时校验密文是否被篡改
        private const val GCM_TAG_BITS = 128

        // 口令派生：PBKDF2-HMAC-SHA256 加盐迭代，把任意长度口令拉伸为 256 位密钥
        private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_DERIVATION_ITERATIONS = 10000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        // GCM 推荐 12 字节随机 nonce，相比 16 字节少一次哈希运算
        private const val IV_LENGTH_BYTES = 12

        // 新密文前缀：解密时据此区分“AES 新密文”与“DES 历史密文”
        private const val AES_CIPHERTEXT_PREFIX = "AES1:"

        /**
         * 计算字符串的摘要，返回十六进制小写字符串。
         * @why 用于笔记锁定密码等场景的哈希比对：只存摘要不存明文，密码本身可校验但不可逆推；
         *      方法名与签名保持原样，调用方无需改动，内部实现已由 MD5 换成 SHA-256。
         * @return 摘要字符串；算法不可用时返回空串（不抛异常，保证调用方功能不中断）
         */
        @JvmStatic
        fun md5(s: String): String = digest(DIGEST_SHA256, s)

        /**
         * 旧版 MD5 摘要，仅供升级前已存储的密码/密保答案做兼容校验。
         * @why 升级前用户设置的锁屏密码以 MD5 存储，直接切换算法会让这些用户无法解锁；
         *      PasswordHelper 在新算法比对失败后回退调用本方法，实现平滑迁移。
         *      新增场景一律使用 md5()（SHA-256），不得调用本方法。
         * @return 32 位十六进制摘要；算法不可用时返回空串
         */
        @JvmStatic
        fun legacyMd5(s: String): String = digest("MD5", s) // NOSONAR 仅兼容历史摘要，新增场景禁止调用

        /**
         * 通用摘要实现：按算法名取 MessageDigest，输出定长补零的十六进制串。
         * @why 原实现用 Integer.toHexString 拼接且不补零，字节值小于 0x10 时会丢失前导 0，
         *      导致同一长度的摘要出现变长、比对时存在理论上的误判；此处统一按两位十六进制补零。
         */
        private fun digest(algorithm: String, s: String): String {
            return try {
                //获取指定摘要算法实例（JDK 内置）
                val messageDigest = MessageDigest.getInstance(algorithm)
                    //把明文字节序列喂入摘要器并完成计算
                    .digest(s.toByteArray(StandardCharsets.UTF_8))

                // Creates Hex String（生成十六进制字符串）
                val hexString = StringBuilder(messageDigest.size * 2)
                for (b in messageDigest) {
                    // 0xFF 掩码去掉符号位，%02x 保证每个字节固定两位，避免摘要变长
                    hexString.append(String.format("%02x", b.toInt() and 0xFF))
                }
                hexString.toString()
            } catch (e: NoSuchAlgorithmException) {
                //算法不存在属环境异常：记日志并返回空串，不让密码流程崩溃
                LogDelegate.w("Something is gone wrong calculating $algorithm", e)
                ""
            }
        }

        /**
         * 使用口令对字符串加密，输出带算法标识的 Base64 密文。
         * @why 锁定笔记的内容在入库前必须加密，防止明文直接落盘（对应 DbHelper.updateNote 中的调用）；
         *      DES 有效密钥仅 56 位、可用穷举破解，改用 AES-256/GCM 并每次生成随机盐与随机 IV，
         *      使同一明文在不同次加密下得到不同密文；密文自带 "AES1:" 前缀，便于解密时识别算法。
         * @return 加密后的 Base64 字符串：加密失败时返回原文（降级保可用）
         */
        @JvmStatic
        fun encrypt(value: String, password: String): String? {
            return try {
                // 随机盐：同一口令在不同设备、不同次加密下派生出的密钥不同
                val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
                // 随机初始向量：保证相同明文每次加密的密文都不同
                val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
                // 口令经 PBKDF2 派生为 256 位 AES 密钥
                val key = deriveKey(password, salt)
                // Cipher is not thread safe（Cipher 不是线程安全）
                val cipher = Cipher.getInstance(AES_TRANSFORMATION)
                // 初始化为加密模式，并绑定本次的 IV
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                // 执行加密
                val cipherText = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
                // 盐、IV 与密文一起编码：解密时先从密文中还原它们，口令只需口令本身
                AES_CIPHERTEXT_PREFIX + Base64.encodeToString(
                    salt + iv + cipherText, Base64.NO_WRAP)
            } catch (e: Exception) {
                // 任何加解密异常都降级返回原文：保证笔记功能可用，错误仅记日志
                LogDelegate.e("Something is gone wrong encrypting", e)
                value
            }
        }

        /**
         * 使用口令解密 Base64 密文，还原明文内容（与 encrypt 对称）。
         * @why 读取锁定便签时调用（对应 DbHelper.getNotes 中的解密分支）：密码正确才能还原明文；
         *      对不带 "AES1:" 前缀的历史数据自动走 DES 兼容分支，升级后旧笔记仍可正常打开。
         * @return 解密后的明文；解密失败（如口令错误、密文损坏）时返回原值，交由上层提示用户
         */
        @JvmStatic
        fun decrypt(value: String?, password: String): String? {
            if (value == null) {
                return null
            }
            return try {
                if (value.startsWith(AES_CIPHERTEXT_PREFIX)) {
                    // 新格式：去掉前缀后按 AES 解密
                    decryptAes(value.substring(AES_CIPHERTEXT_PREFIX.length), password)
                } else {
                    // 旧格式：升级前用 DES 加密的历史密文，只读兼容
                    decryptLegacyDes(value, password)
                }
            } catch (e: Exception) {
                // 口令错误或密文异常都会走到这里：返回原值，不抛异常避免界面崩溃
                LogDelegate.e("Error decrypting", e)
                value
            }
        }

        /**
         * AES 解密：从密文中拆出盐、IV 与密文体，再用 PBKDF2 重建密钥。
         * @why 盐与 IV 随密文一起保存，因此解密只需口令即可重建同一密钥，无需额外字段。
         */
        private fun decryptAes(payload: String, password: String): String {
            // 先把 Base64 文本还原为密文字节
            val raw = Base64.decode(payload, Base64.DEFAULT)
            // 长度不足以容纳盐与 IV 说明密文损坏，直接抛出让上层走降级
            require(raw.size > SALT_LENGTH_BYTES + IV_LENGTH_BYTES) { "Invalid AES ciphertext" }
            val salt = raw.copyOfRange(0, SALT_LENGTH_BYTES)
            val iv = raw.copyOfRange(SALT_LENGTH_BYTES, SALT_LENGTH_BYTES + IV_LENGTH_BYTES)
            val cipherText = raw.copyOfRange(SALT_LENGTH_BYTES + IV_LENGTH_BYTES, raw.size)
            // cipher is not thread safe（密码器不是线程安全）
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            // 字节还原为字符串
            return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        }

        /**
         * DES 兼容解密：仅用于升级前已写入数据库或备份文件的旧密文。
         * @why 维护不要求清空用户已有数据，旧笔记必须仍能打开；该分支不参与任何新增加密，
         *      仅做读取还原，待数据自然迁移完成后可整体删除。
         */
        private fun decryptLegacyDes(value: String, password: String): String {
            // 以口令 UTF-8 字节构造 DES 密钥材料（56 位有效密钥，仅用于还原历史密文）
            val keySpec = DESKeySpec(password.toByteArray(StandardCharsets.UTF_8)) // NOSONAR 历史密文兼容
            // 密钥工厂：把密钥材料规范化为算法可用密钥
            val keyFactory = SecretKeyFactory.getInstance("DES") // NOSONAR 历史密文兼容
            val key = keyFactory.generateSecret(keySpec)
            // 先把 Base64 文本还原为密文字节
            val encryptedPwdBytes = Base64.decode(value, Base64.DEFAULT)
            // 同样新建实例，避免共享 Cipher 的并发问题
            val cipher = Cipher.getInstance("DES") // NOSONAR 历史密文兼容
            // 初始化为解密模式
            cipher.init(Cipher.DECRYPT_MODE, key)
            // 执行解密
            val decryptedValueBytes = cipher.doFinal(encryptedPwdBytes)
            // 字节还原为字符串（UTF-8）
            return String(decryptedValueBytes, StandardCharsets.UTF_8)
        }

        /**
         * 用 PBKDF2-HMAC-SHA256 从口令派生 AES 密钥。
         * @why DESKeySpec 直接把口令字节当密钥，口令短于 8 字节会抛异常，且有效强度只有 56 位；
         *      PBKDF2 通过加盐与万次迭代拉伸，显著提高穷举成本，同时接受任意长度口令。
         */
        private fun deriveKey(password: String, salt: ByteArray): SecretKey {
            val spec = PBEKeySpec(password.toCharArray(), salt,
                KEY_DERIVATION_ITERATIONS, KEY_LENGTH_BITS)
            val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
            // 派生结果按 AES 密钥规格封装
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, AES_ALGORITHM)
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
            // 解析 URI 并压缩连续斜杠，归一化后再判断
            val uri = Uri.parse(path).path?.replace("/+".toRegex(), "/")
            // 命中两种非法形态：①指向内部 /data 目录；②含 ../ 可向上越级
            if (uri?.startsWith("/data")!! || uri.contains("../")) {
                // 拒绝访问并抛出受检异常
                throw ContentSecurityException("Invalid")
            }
        }

    }
}
