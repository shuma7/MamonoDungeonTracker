package com.mamotra.tracker.ocr

import android.util.Log
import com.mamotra.tracker.data.BattleRecord

object BattleResultParser {

    private const val TAG = "MamotraParser"

    // 自分のプレイヤー名 (将来的に設定画面で変更可能にする)
    private const val MY_NAME = "maruo"

    // 前後に他の数字がない 3〜4桁の数字 (ゲーム内レーティング用)
    private val ratingRegex = Regex("""(?<!\d)(\d{3,4})(?!\d)""")

    // 「900 ~ 1099」のようなランク帯範囲テキストを検出するパターン（フォールバック用）
    private val rangePattern = Regex("""(\d{3,4})\s*[~～〜]\s*(\d{3,4})""")

    /**
     * テキスト中の「N ~ N」パターンを除いた文字列を返す（ランク帯誤検出防止）
     */
    private fun removeRangePatterns(text: String): String =
        rangePattern.replace(text, "")

    /**
     * バトル中の通常フレームから自分のレーティングを取得する。
     * 結果画面判定前に呼び出してバトル前レーティングを記録するために使用。
     * ランク帯範囲テキスト（900 ~ 1099 等）は除外する。
     */
    fun extractMyRating(fullText: String): Int {
        val myNameIndex = fullText.indexOf(MY_NAME, ignoreCase = true)
        if (myNameIndex == -1) return 0
        // 「N ~ N」パターンを除いてから検索（ランク帯の誤検出防止）
        val afterMe = removeRangePatterns(fullText.substring(myNameIndex))
        return ratingRegex.findAll(afterMe)
            .map { it.groupValues[1].toInt() }
            .firstOrNull { it in 500..4999 } ?: 0
    }

    /**
     * ロビー画面（結果画面の次フレーム）から新レーティングを取得する。
     * 「BATTLE」テキスト直前の3〜4桁数値を新レーティングとして返す。
     * ランク帯範囲テキストは除外する。
     */
    fun extractPostBattleRating(fullText: String): Int {
        val battleIndex = fullText.indexOf("BATTLE", ignoreCase = true)
        if (battleIndex == -1) return 0
        // 「N ~ N」パターンを除いたテキストで BATTLE より前を検索
        val cleaned = removeRangePatterns(fullText)
        val cleanedBattleIndex = cleaned.indexOf("BATTLE", ignoreCase = true)
        if (cleanedBattleIndex == -1) return 0
        val beforeBattle = cleaned.substring(0, cleanedBattleIndex)
        return ratingRegex.findAll(beforeBattle)
            .map { it.groupValues[1].toInt() }
            .lastOrNull { it in 500..4999 } ?: 0
    }

    /**
     * ロビー画面テキストとバトル前レーティングから BattleRecord を生成する（二段階検出用）。
     * @param lobbyText      ロビー画面の OCR テキスト
     * @param preBattleRating バトル開始前に追跡した自分のレーティング
     * @param resultScreenRating 結果画面で取得した自分のレーティング（相手レーティング取得に使用）
     * @param opponentName 相手プレイヤー名（結果画面から取得済み）
     * @param opponentRating 相手プレイヤーレーティング（結果画面から取得済み）
     * @param ocrTrophyChange 結果画面の OCR トロフィー変動値
     */
    fun parseFromLobby(
        lobbyText: String,
        preBattleRating: Int,
        resultScreenRating: Int,
        opponentName: String,
        opponentRating: Int,
        ocrTrophyChange: Int
    ): BattleRecord? {
        Log.d(TAG, "--- parseFromLobby() 開始 ---")
        val postRating = extractPostBattleRating(lobbyText)
        Log.d(TAG, "postRating=$postRating  preBattleRating=$preBattleRating  ocrTrophyChange=$ocrTrophyChange")

        val ratingDiff = if (preBattleRating > 0 && postRating > 0) postRating - preBattleRating else 0
        Log.d(TAG, "ratingDiff(lobby)=$ratingDiff")

        val result = when {
            ocrTrophyChange > 0 -> "WIN"
            ocrTrophyChange < 0 -> "LOSE"
            ratingDiff > 0      -> "WIN"
            ratingDiff < 0      -> "LOSE"
            else -> {
                Log.d(TAG, "parseFromLobby: WIN/LOSE 判定不能 → null")
                return null
            }
        }
        Log.d(TAG, "result(lobby)=$result")

        val trophyChange = when {
            ocrTrophyChange != 0 -> ocrTrophyChange
            ratingDiff != 0      -> ratingDiff
            else                 -> 0
        }

        // 結果画面レーティングが取れていればそちらを優先、なければロビー新レーティングを使用
        val finalMyRating = if (resultScreenRating > 0) resultScreenRating
                            else if (postRating > 0) postRating
                            else preBattleRating

        return BattleRecord(
            timestamp      = System.currentTimeMillis(),
            result         = result,
            myRating       = finalMyRating,
            opponentName   = opponentName,
            opponentRating = opponentRating,
            trophyChange   = trophyChange
        )
    }

    /**
     * 結果画面の解析結果を保持するデータクラス。
     * WIN/LOSE が確定している場合は record != null。
     * 判定不能で次フレーム（ロビー）待ちの場合は record == null かつ pendingData != null。
     */
    data class ParseResult(
        val record: BattleRecord? = null,
        val pendingData: PendingBattleData? = null
    )

    /**
     * 結果画面で判定できなかった場合にロビー画面まで持ち越すデータ。
     */
    data class PendingBattleData(
        val preBattleRating: Int,
        val resultScreenRating: Int,
        val opponentName: String,
        val opponentRating: Int,
        val ocrTrophyChange: Int
    )

    /**
     * @param fullText      OCR テキスト
     * @param preBattleRating バトル開始前に追跡した自分のレーティング（0の場合は不使用）
     * @return ParseResult (record が確定済み or pendingData でロビー待ち or 両方 null でスキップ)
     */
    fun parse(fullText: String, preBattleRating: Int = 0): ParseResult {
        Log.d(TAG, "--- parse() 開始 ---")

        // Step 1: 「獲得ポイント」の部分一致で結果画面を判定
        // OCRは「獲得ポイント」を「獲得ポシト0」「獲得ポント0」等に誤読するため部分一致を使う
        if (!fullText.contains("獲得ポ")) {
            Log.d(TAG, "「獲得ポ」未検出 → スキップ")
            return ParseResult()
        }
        Log.d(TAG, "「獲得ポ」検出 → 結果画面と判定")

        // Step 2: 自分のレーティング（結果画面での現在値）を取得
        val myRating = extractMyRating(fullText)
        Log.d(TAG, "myRating=$myRating  preBattleRating=$preBattleRating")

        // Step 3: トロフィー変動をテキストから抽出（+N / -N）
        val ocrTrophyChange = extractTrophyChange(fullText)
        Log.d(TAG, "ocrTrophyChange=$ocrTrophyChange")

        // Step 4: レーティング差分（OCRで変動値が読めなかった場合のフォールバック）
        val ratingDiff = if (preBattleRating > 0 && myRating > 0) myRating - preBattleRating else 0
        Log.d(TAG, "ratingDiff=$ratingDiff")

        // Step 5: 相手のレーティングと名前を取得（WIN/LOSE 判定の前に抽出して pending にも使えるようにする）
        val myNameIndex = fullText.indexOf(MY_NAME, ignoreCase = true)
        val opponentRating = if (myNameIndex != -1) {
            val beforeMe = fullText.substring(0, myNameIndex)
            ratingRegex.findAll(beforeMe)
                .map { it.groupValues[1].toInt() }
                .lastOrNull { it in 500..4999 } ?: 0
        } else 0
        val opponentName = extractOpponentName(fullText, opponentRating)

        // Step 6: WIN/LOSE 判定
        // 優先順位: OCRで直接読めたテキスト > OCRの変動値 > レーティング差
        val result = when {
            fullText.contains("WIN",  ignoreCase = true) -> "WIN"
            fullText.contains("LOSE", ignoreCase = true) -> "LOSE"
            ocrTrophyChange > 0 -> "WIN"
            ocrTrophyChange < 0 -> "LOSE"
            ratingDiff > 0      -> "WIN"
            ratingDiff < 0      -> "LOSE"
            else -> {
                // 判定不能 → ロビー画面で新レーティングを確認するために pending に切り替え
                Log.d(TAG, "WIN/LOSE 判定不能 → pendingData を返してロビー待ち")
                return ParseResult(
                    pendingData = PendingBattleData(
                        preBattleRating    = preBattleRating,
                        resultScreenRating = myRating,
                        opponentName       = opponentName,
                        opponentRating     = opponentRating,
                        ocrTrophyChange    = ocrTrophyChange
                    )
                )
            }
        }
        Log.d(TAG, "result=$result")

        // Step 7: trophyChange を確定
        // OCRから取れた場合はそれを、取れない場合はレーティング差で代替
        val trophyChange = when {
            ocrTrophyChange != 0 -> ocrTrophyChange
            ratingDiff != 0      -> ratingDiff
            else                 -> 0
        }

        Log.d(TAG, "opponentName=$opponentName  opponentRating=$opponentRating  trophyChange=$trophyChange")

        return ParseResult(
            record = BattleRecord(
                timestamp      = System.currentTimeMillis(),
                result         = result,
                myRating       = if (myRating > 0) myRating else preBattleRating,
                opponentName   = opponentName,
                opponentRating = opponentRating,
                trophyChange   = trophyChange
            )
        )
    }

    private fun extractOpponentName(text: String, opponentRating: Int): String {
        if (opponentRating == 0) return "不明"
        val ratingStr   = opponentRating.toString()
        val ratingIndex = text.indexOf(ratingStr)
        if (ratingIndex > 0) {
            val before = text.substring(0, ratingIndex).trim()
            val lines  = before.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val name   = lines.lastOrNull() ?: return "不明"
            return name.replace(Regex("[|Ⅱ|ポーズ|倍速|オート].*"), "").trim().ifBlank { "不明" }
        }
        return "不明"
    }

    private fun extractTrophyChange(text: String): Int {
        // 「獲得ポ」以降から +N / -N を探す（部分一致で検索）
        val pointsIndex = text.indexOf("獲得ポ")
        val searchText  = if (pointsIndex != -1) text.substring(pointsIndex) else text
        val changeRegex = Regex("""([+\-])\s*(\d+)""")
        val match = changeRegex.find(searchText) ?: return 0
        val sign  = if (match.groupValues[1] == "-") -1 else 1
        val value = match.groupValues[2].toIntOrNull() ?: 0
        return sign * value
    }
}
