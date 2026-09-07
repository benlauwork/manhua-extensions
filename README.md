# Manhua Extensions

呢個專案係一個全站 Mihon extension repository，而唔係針對單一漫畫。目前包含：

- [漫畫160](https://www.mh160mh.com)：熱門、最新、搜尋、漫畫詳情、全章節及 qTcms 圖片解碼。
- [動漫屋 DM5](https://www.dm5.cn)：漫畫分類、排行、最新、搜尋、全章節、加密圖片 URL、Cookie 清理及可選章末吐槽頁。

兩個 extension 會分別建立 APK／JAR，但一齊寫入同一個 `index.pb`：

```text
兩個網站 → 兩個已簽名 extensions → 同一 repo branch/index.pb → Mihon
```

## 發佈到你自己嘅 GitHub

1. 建立一個空 GitHub repository，將本專案推上 `main` branch。
2. 安裝 JDK 17 同 [GitHub CLI](https://cli.github.com/)，再登入 `gh auth login`。
3. 喺專案根目錄執行：

   ```bash
   ./scripts/setup-signing.sh benlauwork/manhua-extensions
   ```

   呢個指令會建立私人簽署金鑰，並設定 `SIGNING_KEY`、`ALIAS`、`KEY_STORE_PASSWORD`、`KEY_PASSWORD` 四個 GitHub Actions secrets。`signingkey.jks` 已被 `.gitignore` 排除；請另外安全備份，唔好上載或公開。

4. 去 GitHub Actions 手動執行 **Build and publish Mihon repository**。之後每次推送 `main` 都會自動重新發佈。
5. 成功後，將以下地址加入 Mihon 嘅擴充套件儲存庫：

   ```text
   https://github.com/benlauwork/manhua-extensions/raw/repo/index.pb
   ```

   Tachimanga（iOS）請改用佢支援嘅 legacy JSON repository：

   ```text
   https://raw.githubusercontent.com/benlauwork/manhua-extensions/repo/index.min.json
   ```

更新某個 extension 時，增加對應 [`Manga 160 versionCode`](src/zh/manga160/build.gradle.kts) 或 [`DM5 versionCode`](src/zh/dm5/build.gradle.kts)，再推送。簽署金鑰必須一直沿用同一個，否則已安裝用戶唔會視新 APK 為可信更新。

DM5 保留咗上游 extension 嘅 package 同 source ID，方便接續原有書庫資料。由於你私人 repo 使用自己嘅簽署金鑰，如果裝置已有 Keiyoushi 官方 DM5 extension，需要先移除官方 APK，先可以安裝呢個私人簽名版本；兩者唔可以同時安裝。

## 本機編譯

本機需要 JDK 17 同 Android SDK：

```bash
./gradlew \
  :src:zh:manga160:lintRelease :src:zh:manga160:assembleDebug \
  :src:zh:dm5:lintRelease :src:zh:dm5:assembleDebug
```

主要 extractor 位於 [`Manga160.kt`](src/zh/manga160/src/eu/kanade/tachiyomi/extension/zh/manga160/Manga160.kt) 同 [`Dm5.kt`](src/zh/dm5/src/eu/kanade/tachiyomi/extension/zh/dm5/Dm5.kt)。如果網站改版，就需要更新相應 selector 或圖片解碼規則。

## 授權及責任

建置系統及 DM5 extension 衍生自 [Keiyoushi Extensions Source](https://github.com/keiyoushi/extensions-source)，依 Apache License 2.0 發佈，詳見 [`LICENSE`](LICENSE) 及 [`NOTICE`](NOTICE)。本專案只提供讀取網站資料嘅客戶端程式；你需要自行確保網站內容、圖片及對外發佈方式符合你擁有嘅權利及適用條款。
