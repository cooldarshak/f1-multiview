package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Test

class HevcPpsTileInspectorTest {
    private fun ppsWithTilesEnabled(enabled: Boolean): ByteArray {
        val bits = buildString {
            append("1")     // pps_pic_parameter_set_id = ue(0)
            append("1")     // pps_seq_parameter_set_id = ue(0)
            append("0")     // dependent_slice_segments_enabled_flag
            append("0")     // output_flag_present_flag
            append("000")   // num_extra_slice_header_bits
            append("0")     // sign_data_hiding_enabled_flag
            append("0")     // cabac_init_present_flag
            append("1")     // num_ref_idx_l0_default_active_minus1 = ue(0)
            append("1")     // num_ref_idx_l1_default_active_minus1 = ue(0)
            append("1")     // init_qp_minus26 = se(0)
            append("0")     // constrained_intra_pred_flag
            append("0")     // transform_skip_enabled_flag
            append("0")     // cu_qp_delta_enabled_flag
            append("1")     // pps_cb_qp_offset = se(0)
            append("1")     // pps_cr_qp_offset = se(0)
            append("0")     // pps_slice_chroma_qp_offsets_present_flag
            append("0")     // weighted_pred_flag
            append("0")     // weighted_bipred_flag
            append("0")     // transquant_bypass_enabled_flag
            append(if (enabled) "1" else "0") // tiles_enabled_flag
        }
        val rbsp = ByteArray((bits.length + 7) / 8)
        bits.forEachIndexed { index, bit ->
            if (bit == '1') {
                rbsp[index / 8] = (rbsp[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
            }
        }
        return byteArrayOf(0, 0, 0, 1, 0x44, 0x01) + rbsp
    }

    @Test
    fun readsTilesEnabledFlagFromPps() {
        assertEquals(true, HevcPpsTileInspector.tilesEnabled(ppsWithTilesEnabled(true)))
        assertEquals(false, HevcPpsTileInspector.tilesEnabled(ppsWithTilesEnabled(false)))
    }

    @Test
    fun missingPpsDoesNotInventTileEvidence() {
        val nonPpsNal = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x00)
        assertEquals(null, HevcPpsTileInspector.tilesEnabled(nonPpsNal))
    }
}
