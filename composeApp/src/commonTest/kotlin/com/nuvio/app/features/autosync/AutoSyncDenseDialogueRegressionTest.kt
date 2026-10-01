package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression from a reported run (Ludwig S02E04): an external subtitle already in sync with the
 * embedded English track was moved 1.5 s early. Its dense, back-to-back dialogue makes whole-
 * timeline coverage nearly identical for every offset from -1.5 s to 0 s.
 */
class AutoSyncDenseDialogueRegressionTest {
    @Test
    fun alreadySyncedDenseDialogueKeepsZeroOffset() {
        val target = decodeFixture(TARGET)
        val reference = decodeFixture(REFERENCE)
        val result = assertNotNull(AutoSyncTimelineRetimer.retime(
            reference = reference.first,
            target = target.first,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
            referenceEstimatedEndStartsMs = reference.second,
        ))
        assertTrue(result.confident, result.rejectReason)
        assertTrue(abs(result.alignmentInterceptMs) <= 100.0, "intercept=${result.alignmentInterceptMs}")
        assertTrue(result.maxAlignmentShiftMs() <= 100.0, "maxShift=${result.maxAlignmentShiftMs()}")
    }

    private fun decodeFixture(line: String): Pair<List<SubtitleSyncCue>, Set<Long>> {
        var start = 0L
        val estimated = mutableSetOf<Long>()
        val cues = line.substringAfter(" data=").split(',').map { token ->
            val timing = token.removeSuffix("~")
            start += timing.substringBefore('.').toLong(36)
            val duration = timing.substringAfter('.').toLong(36)
            if (token.endsWith("~")) estimated += start
            SubtitleSyncCue(start, start + duration, "")
        }
        return cues to estimated
    }

    private companion object {
        const val TARGET =
            "TIMING label=target:0 sdh=0 n=977 data=" +
                "1jk.tc,u0.1u0,1uo.2p4,2ps.114,11s.1sw,1tk.2ig,2j4.128,12w.1k0,1ko.1fk,1g8.2uo,2vc.1w8,1ww.1hs,1i" +
                "g.240,24o.1xc,1y0.s8,sw.1nc,1o0.21s,22g.wo,xc.2ig,2j4.1go,1hc.15k,168.1u0,1uo.40o,f14.268,26w.1m" +
                "8,1mw.33k,348.254,25s.2lh,e2o.34g,10mw.1hs,1ig.300,60o.2ag,k7s.1w8,1ww.20o,21c.1b4,1bs.3s0,3so.2" +
                "wo,4kg.1iw,1jk.20o,21c.1fk,1g8.1rs,1sg.s8,sw.24w,6q8.274,184w.2ds,2xk.2ow,54g.2cw,2dk.100,10o.17" +
                "s,18g.1k0,1ko.2y0,2yo.2mw,2nk.2f4,2fs.1iw,1jk.1rs,1sg.2mw,2nk.22w,23k.34o,35c.37s,40g.100,10o.1g" +
                "o,1hc.1hs,1ig.3a8,3aw.1sw,1tk.1w8,1ww.22w,23k.3k0,3w0.20g,3uw.2ls,2mg.3eg,4yw.2r4,4lk.37s,daw.22" +
                "1,2vc.1xc,1y0.15k,168.1dc,1e0.1a0,1ao.1c8,1cw.1fk,1g8.2ds,348.1zk,208.2q8,2qw.114,11s.2wo,3n4.2u" +
                "o,2vc.1fk,1g8.13c,140.1v4,1vs.1l4,1ls.29c,2u8.1pk,1q8.3wg,3x4.29k,2a8.2bk,2i0.1fk,1g8.35s,36g.20" +
                "g,4xs.2vs,2wg.32g,334.1nd,1o1.1m7,1mv.14g,154.1a0,1ao.260,488.1hs,1ig.1v4,1vs.2i8,d6g.2xs,7yo.13" +
                "c,140.xs,yg.2sg,2t4.1zk,208.1m8,1mw.1yg,1z4.2ug,37k.2ug,40g.2sg,2t4.338,5a0.3cg,3d4.wo,xc.1m8,1m" +
                "w.3bc,3c0.1sw,1tk.128,12w.1go,1hc.20o,21c.32g,334.1go,1hc.jd,k1.1iv,1jj.yw,zk.2ls,2mg.1yg,1z4.1b" +
                "4,1bs.468,4h4.2f4,2fs.34o,35c.1qo,1rc.2ko,2lc.31c,320.1sw,1tk.128,12w.1hs,1ig.1xc,1y0.1fk,1g8.2g" +
                "8,2gw.2vs,2wg.xs,yg.1go,1hc.2tk,2u8.1dc,1e0.1fk,1g8.1qo,1rc.1rs,1sg.xs,yg.128,12w.2p4,2ps.2cw,2d" +
                "k.3fk,3w0.20g,2gw.2ag,99c.1og,1p4.15k,168.2cw,2dk.1iw,1jk.240,24o.34g,4i8.35s,36g.1hs,1ig.21s,22" +
                "g.2sg,2t4.1fk,1g8.2cw,2dk.1yg,1z4.1go,1hc.1xc,1y0.2ns,4xs.wo,xc.2wo,3e8.380,38o.1iw,1jk.2e0,2eo." +
                "2ko,2lc.1zk,208.1c8,1cw.2tc,8y8.27c,280.20g,3so.15k,168.14g,154.1fk,1g8.1nc,1o0.1go,1hc.1go,1hc." +
                "2uo,2vc.wo,xc.2f4,2fs.2tk,2u8.3zs,40g.18w,19k.2q8,2qw.2ww,2xk.29k,2a8.1zk,208.114,11s.1go,1hc.1f" +
                "k,1g8.268,26w.37s,3d4.3v4,42o.100,10o.36o,3d4.s8,sw.29c,44w.440,bbs.22w,23k.2ww,2xk.1eg,1f4.2uo," +
                "2vc.1zk,208.1l4,1ls.21s,22g.1v4,1vs.2f4,2fs.16o,17c.1fk,1g8.1nc,1o0.2g8,2gw.1fk,1g8.2q8,2qw.3gw," +
                "3hk.2ig,2j4.29k,2a8.35s,36g.1l4,1ls.yw,zk.2ig,2j4.1pk,1q8.20o,21c.3a8,3aw.1zk,208.2cw,2dk.1w8,1w" +
                "w.29c,7mg.1hs,1ig.2ls,2mg.xs,yg.1zk,208.1zk,208.21k,a00.2ig,2j4.2wk,3n4.3dk,3e8.20g,4yw.2bs,2cg." +
                "17s,18g.1nc,1o0.2ns,474.1sw,1tk.1u0,1uo.1rs,1sg.15k,168.2dx,2el.sb,sz.1pk,1q8.2uo,2vc.29k,2a8.1l" +
                "4,1ls.2q8,2qw.13c,140.1eg,1f4.1b4,1bs.1l4,1ls.100,10o.1qo,1rc.33k,348.128,12w.1sw,1tk.1l4,1ls.2a" +
                "o,2bc.14g,154.1hs,1ig.2vs,2wg.1pk,1q8.18w,19k.15k,168.1l4,1ls.1hs,1ig.14g,154.3oo,3pc.2zw,3d4.2g" +
                "8,2gw.13c,140.1dc,1e0.3iw,5rs.13c,140.114,11s.1c8,1cw.35s,36g.1w8,1ww.ug,v4.1xc,1y0.1dc,1e0.114," +
                "11s.2p4,2ps.1u0,1uo.380,38o.3mg,3n4.2ww,2xk.2uo,2vc.254,25s.2jk,2k8.20g,30w.2jk,2k8.1k0,1ko.1sw," +
                "1tk.2jk,2k8.yw,zk.1qo,1rc.1dc,1e0.128,12w.5vk,5w8.2hc,2i0.24w,280.2ko,2lc.20g,5m8.29c,afk.1b4,1b" +
                "s.2hc,2i0.2mw,2nk.1fk,1g8.3eg,460.xs,yg.27c,280.1nc,1o0.22o,4ns.274,3w0.1k0,1ko.1m8,1mw.1a0,1ao." +
                "1pk,1q8.1v4,1vs.20o,21c.2ww,2xk.1hs,1ig.240,24o.1zk,208.1fk,1g8.1pk,1q8.1k0,1ko.2cw,2dk.2y0,2yo." +
                "2yl,2z9.28r,2jn.1og,1p4.s8,sw.1qo,1rc.2ug,2xk.2rc,2s0.268,26w.300,334.2p4,2ps.xs,yg.vk,w8.2rc,2s" +
                "0.2jk,2k8.vk,w8.268,26w.2ds,2s0.18w,19k.22w,23k.28g,294.29c,a4g.1w8,1ww.1hs,1ig.xs,yg.3hs,6jk.28" +
                "8,8js.28g,294.16o,17c.1go,1hc.1yg,1z4.vk,w8.2f4,2fs.1rs,1sg.13c,140.1nc,1o0.2z4,2zs.1m8,1mw.2e0," +
                "2eo.2cw,2dk.1u0,1uo.268,26w.254,25s.18w,19k.1pk,1q8.328,3c0.1sw,1tk.380,38o.2bs,2cg.3a8,3aw.2sg," +
                "2t4.29k,2a8.28g,294.4y8,4yw.1b4,1bs.1k0,1ko.20g,4yw.268,26w.3pk,l2w.300,5fk.15k,168.1a0,1ao.3m8," +
                "ac8.29k,2a8.254,25s.1m8,1mw.268,26w.1go,1hc.2lk,54g.274,2u8.20g,2s0.1b4,1bs.1dc,1e0.308,30w.268," +
                "26w.1u0,1uo.2g8,2gw.1yg,1z4.32g,334.1hs,1ig.1xc,1y0.15k,168.3a8,3aw.2co,460.2g0,6mw.2jc,4g0.14g," +
                "154.2q0,3e8.20g,22g.24w,960.1go,1hc.114,11s.240,24o.2sg,2t4.1eg,1f4.33k,348.20g,ens.27c,280.1w8," +
                "1ww.1hs,1ig.1sw,1tk.2jc,3w0.240,24o.2p4,2ps.1m8,1mw.ug,v4.1l4,1ls.1zk,208.3l4,42o.1b4,1bs.1xc,1y" +
                "0.1c8,1cw.20o,21c.1a0,1ao.1go,1hc.3go,3rk.2z4,2zs.29k,2a8.3og,4q0.28g,294.1qo,1rc.1sw,1tk.2p4,2p" +
                "s.268,26w.1fk,1g8.2ao,2bc.1iw,1jk.1sw,1tk.21s,22g.254,25s.1nc,1o0.2wo,36g.2tc,3n4.2sg,2t4.3zs,40" +
                "g.1u0,1uo.100,10o.2y0,2yo.2rc,2s0.2y0,2yo.2rc,2s0.380,38o.2rc,2s0.1k0,1ko.21s,22g.yw,zk.1nc,1o0." +
                "29k,2a8.14g,154.1m8,1mw.2f4,2fs.3s0,3so.1v4,1vs.1rs,1sg.1k0,1ko.20o,21c.1eg,1f4.1qo,1rc.20o,21c." +
                "1go,1hc.2vs,2wg.1go,1hc.18w,19k.1pk,1q8.1w8,1ww.1eg,1f4.1go,1hc.300,348.1nc,1o0.1k0,1ko.2f4,2fs." +
                "1go,1hc.1fk,1g8.22o,3y8.22o,i20.22w,23k.1og,1p4.jv,kj.1p1,1pp.274,39s.1zk,208.1nc,1o0.1og,1p4.1a" +
                "0,1ao.1go,1hc.3og,7dk.2p4,2ps.17s,18g.15k,168.1yg,1z4.1yg,1z4.14g,154.1sw,1tk.22w,23k.2mw,2nk.2e" +
                "0,2eo.29k,2a8.3iw,44w.3l4,3qg.20g,2wg.20g,aog.288,7vc.20g,294.2g0,3js.37s,4g0.1dc,1e0.21k,4co.2l" +
                "k,2vc.3b4,6ko.20g,4ag.1l4,1ls.3t4,3ts.2p4,2ps.34o,35c.29k,2a8.2e0,2eo.2s8,72g.29k,2a8.5kg,5l4.1o" +
                "g,1p4.13c,140.3ps,3qg.1u0,1uo.2co,2j4.3j4,3js.28g,294.2mo,2nk.mx,nl.1y7,1yv.1u0,1uo.14g,154.1yg," +
                "1z4.1fk,1g8.2ag,3aw.4p4,4ug.20g,474.1l4,1ls.3s0,3so.17s,18g.4e8,4ew.1nc,1o0.2jk,2k8.1pk,1q8.2co," +
                "4r4.21s,22g.2q8,2qw.2ds,2yo.20g,25s.5lc,6o0.128,12w.114,11s.1v4,1vs.35k,4vk.20o,21c.13c,140.2hc," +
                "2i0.49s,4ag.114,11s.2ko,2lc.3xc,86g.2vs,2wg.308,30w.2cw,2dk.2jk,2k8.1eg,1f4.2ig,2j4.18w,19k.23s," +
                "3w0.1a0,1ao.2ew,3ts.314,4s8.3k0,3kw.2cw,2dk.240,24o.2yw,f4g.1dc,1e0.2ao,2bc.2p4,2ps.1sw,1tk.2jk," +
                "2k8.21s,22g.3k0,3w0.tc,u0.20g,os8.1go,1hc.1u0,1uo.3vc,3w0.28g,294.254,25s.xs,yg.3i0,3io.2ko,2lc." +
                "1pk,1q8.1u0,1uo.1xc,1y0.1rs,1sg.15k,168.20g,26w.3mg,3n4.1hs,1ig.2z4,2zs.254,25s.1v4,1vs.240,24o." +
                "2tk,2u8.254,25s.40o,4mo.13c,140.300,4s8.100,10o.1dc,1e0.2ds,av4.yw,zk.2xs,38o.k6,ku.2y2,3ca.2bs," +
                "2cg.36w,37k.3s0,3so.2g8,2gw.1yg,1z4.16o,17c.1sw,1tk.2jk,2k8.2mw,2nk.1hs,1ig.1v4,1vs.36w,37k.20o," +
                "21c.1v4,1vs.1iw,1jk.1l4,1ls.20o,21c.29c,2u8.1dc,1e0.22w,23k.268,26w.1og,1p4.1m8,1mw.2o0,2oo.23s," +
                "280.420,42o.308,30w.1w8,1ww.1b4,1bs.4f4,c3k.1yg,1z4.1fk,1g8.1nc,1o0.114,11s.18w,19k.1go,1hc.22w," +
                "23k.2sg,2t4.1k0,1ko.1a0,1ao.2sg,2t4.268,26w.2o0,2oo.1iw,1jk.3j4,3js.35s,36g.2ig,2j4.3cg,3d4.240," +
                "24o.240,24o.wo,xc.254,25s.xs,yg.1a0,1ao.3w8,54g.1b4,1bs.3og,71c.3j4,3js.298,13ns.16o,17c.1a0,1ao" +
                ".1l4,1ls.2bs,2cg.1qo,1rc.1xc,1y0.2cw,2dk.2tk,2u8.28g,294.2tc,3rk.3bc,3c0.3eo,3fc.33k,348.2bk,gg8" +
                ".2e0,2eo.2rc,2s0.2ls,2mg.3v4,8kw.20o,21c.1yg,1z4.2q8,2qw.3i0,3io.3dk,3e8.18w,19k.15k,168.2hc,2i0" +
                ".1u0,1uo.xs,yg.wo,xc.34g,5og.268,26w.1fc,1g0.1cg,1d4.1v4,1vs.1go,1hc.49s,4ag.3go,3so.1xc,1y0.2jc" +
                ",2qw.1nc,1o0.114,11s.20g,22g.240,24o.29c,2a8.1yg,1z4.1eg,1f4.1yg,1z4.22o,39s.100,10o.268,26w.1k0" +
                ",1ko.2f4,2fs.2e0,2eo.34o,35c.2mo,334.1go,1hc.114,11s.2f4,2fs.3pk,4kg.1yg,1z4.22o,488.2ls,2mg.28g" +
                ",294.2h4,4bk.254,25s.2bs,2cg.1u0,1uo.13c,140.2vk,3n4.15k,168.1m8,1mw.1og,1p4.46g,474.240,24o.1l4" +
                ",1ls.1a0,1ao.2ko,2lc.1iw,1jk.29c,3hk.2mw,2nk.17s,18g.1iw,1jk.1k0,1ko.28g,294.48g,4r4.2ag,2qw.2tc" +
                ",36g.13c,140.1xc,1y0.20o,21c.15k,168.1sw,1tk.1rs,1sg.1fk,1g8.1eg,1f4.3mg,3n4.260,2yo.2co,2j4.1m8" +
                ",1mw.37s,3rk.2wo,3ts.114,11s.2ao,2bc.21s,22g.3rs,5a0.2e0,2eo.20g,2s0.2h4,2t4.1k0,1ko.2ig,2j4.xs," +
                "yg.2co,39s.1fk,1g8.1a0,1ao.1rs,1sg.20o,21c.1sw,1tk.2uo,2vc.2tc,3m0.1m8,1mw.1nc,1o0.100,10o.1dc,1" +
                "e0.15k,168.1m8,1mw.20g,23k.20g,38o.1v4,1vs.1a0,1ao.31c,320.128,12w.1xc,1y0.27c,280.16o,17c.1b4,1" +
                "bs.1dc,1e0.2lk,3aw.1pk,1q8.2tk,2u8.1og,1p4.20o,21c.288,3kw.ug,v4.2e0,2eo.3b4,4ew.14g,154.27c,280" +
                ".1a0,1ao.1sw,1tk.4f4,4i8.1nc,1o0.1k0,1ko.1w8,1ww.2sg,2t4.16o,17c.23s,2t4.2ds,2k8.114,11s.42w,5zk" +
                ".288,c94.17s,18g.16o,17c.wo,xc.1sw,1tk.20o,21c.254,25s.3xc,5hs.2q0,3js.100,10o.2ds,4xs.23s,294.u" +
                "g,v4.2ew,b8g.1b4,1bs.1l4,1ls.20o,21c.1nc,1o0.1fk,1g8.2ao,2bc.268,26w.vk,w8.1v4,1vs.wo,xc.1l4,1ls" +
                ".1iw,1jk.1m8,1mw.1dc,1e0.20o,21c.yw,zk.1zk,208.13c,140.1hs,1ig.20c,5pk.1k0,1ko.wo,xc.tc,u0.1fk,1" +
                "g8.128,12w.2ao,2bc.1c8,1cw.1u0,1uo.1eg,1f4.128,12w.20o,21c.1k0,1ko.28g,294.2tk,2u8.3cg,3d4.tc,u0" +
                ".1m8,1mw.13c,140.23s,4ns.20o,21c.308,30w.288,36g.260,3e8.2ns,4ew.20g,26w.14g,154.1a0,1ao.1eg,1f4" +
                ".1yg,1z4.2mo,76w.2vk,334.2ns,e1k.2ow,54g.2tc,69k.100,10o.1m8,1mw.1zk,208.128,12w.1og,1p4.z3,zr.b" +
                "d,c1.2xs,30w.1m8,1mw.2z4,2zs.1k0,1ko.13c,140.32g,334.1hs,1ig.100,10o.100,10o.1lx,1ml.1xn,1yb.1yg" +
                ",1z4.2o0,2oo.13c,140.2e0,2eo.4ko,65k.ppx"
        const val REFERENCE =
            "TIMING label=ref:mkv-cues:3 sdh=0 n=990 data=" +
                "1jk.tz,u0.1un,1uo.2pr,2ps.11r,11s.1tj,1tk.2j3,2j4.12v,12w.1kn,1ko.1g7,1g8.2vb,2vc.1wv,1ww.1if,1i" +
                "g.24n,24o.1xz,1y0.sv,sw.1nz,1o0.22f,22g.xb,xc.2j3,2j4.1hb,1hc.167,168.1un,1uo.2t4,90g.1jk,4vk.15" +
                "3,154.26v,26w.1mv,1mw.347,348.25r,25s.2s0,e2o.1ww,960.21c,rgw.1if,1ig.1sg,60o.12w,k7s.1wv,1ww.21" +
                "b,21c.1br,1bs.3sn,3so.1p4,4kg.1jj,1jk.21b,21c.1g7,1g8.1sf,1sg.sv,sw.xc,3d4.sw,21c.1br,1bs.zk,184" +
                "w.168,2xk.1hc,54g.2dj,2dk.10n,10o.18f,18g.1kn,1ko.2yn,2yo.2nj,2nk.2fr,2fs.1jj,1jk.1sf,1sg.2nj,2n" +
                "k.23j,23k.35b,35c.208,40g.10n,10o.1hb,1hc.1if,1ig.2fs,3aw.1tj,1tk.1wv,1ww.23j,23k.2cg,3w0.sw,3uw" +
                ".2mf,2mg.26w,4yw.1jk,4lk.208,8io.sw,4s8.1p4,2vc.1xz,1y0.167,168.1dz,1e0.1an,1ao.1cv,1cw.1g7,1g8." +
                "168,348.207,208.2qv,2qw.11r,11s.1p4,3n4.2vb,2vc.1g7,1g8.13z,140.1vr,1vs.1lr,1ls.11s,2u8.1q7,1q8." +
                "3x3,3x4.154,2a8.140,2i0.1g7,1g8.36f,36g.sw,4xs.2wf,2wg.333,334.11r,11s.18g,294.153,154.1an,1ao.y" +
                "g,488.1if,1ig.1vr,1vs.1ao,bz4.17b,17c.1q8,7yo.13z,140.yf,yg.2t3,2t4.207,208.1mv,1mw.1z3,1z4.1mw," +
                "37k.1mw,40g.2t3,2t4.1vr,1vs.10o,3e8.25s,3d4.xb,xc.1mv,1mw.3bz,3c0.1tj,1tk.12v,12w.1hb,1hc.21b,21" +
                "c.333,334.1hb,1hc.23j,23k.zj,zk.2mf,2mg.1z3,1z4.1br,1bs.2yo,4h4.2fr,2fs.25s,35c.1rb,1rc.2lb,2lc." +
                "31z,320.sw,1tk.12v,12w.1if,1ig.1xz,1y0.1g7,1g8.2gv,2gw.1tk,2wg.yf,yg.1hb,1hc.2u7,2u8.1dz,1e0.1g7" +
                ",1g8.1rb,1rc.1sf,1sg.yf,yg.12v,12w.2pr,2ps.1ao,2dk.280,3w0.sw,2gw.12w,99c.1p3,1p4.167,168.2dj,2d" +
                "k.1jj,1jk.24n,24o.1ww,4i8.36f,36g.1if,1ig.22f,22g.1sg,2t4.1g7,1g8.2dj,2dk.1z3,1z4.1hb,1hc.1xz,1y" +
                "0.1g8,4xs.xb,xc.1p4,3e8.2a8,38o.1jj,1jk.2en,2eo.2lb,2lc.207,208.1cv,1cw.1ls,8y8.27z,280.sw,3so.1" +
                "67,168.153,154.1g7,1g8.1nz,1o0.1hb,1hc.1hb,1hc.23k,2vc.xb,xc.2fr,2fs.2u7,2u8.36g,40g.19j,19k.2qv" +
                ",2qw.2xj,2xk.2a7,2a8.207,208.11r,11s.1hb,1hc.1g7,1g8.26v,26w.208,3d4.2nk,42o.10n,10o.1z4,3d4.sv," +
                "sw.11s,44w.2wg,bbs.23j,23k.2xj,2xk.1f3,1f4.2vb,2vc.207,208.1lr,1ls.22f,22g.1vr,1vs.2fr,2fs.17b,1" +
                "7c.1g7,1g8.1nz,1o0.2gv,2gw.1g7,1g8.2qv,2qw.3hj,3hk.1g8,2j4.2a7,2a8.36f,36g.1lr,1ls.zj,zk.2j3,2j4" +
                ".xc,1q8.21b,21c.3av,3aw.207,208.2dj,2dk.1wv,1ww.11s,7mg.1if,1ig.2mf,2mg.yf,yg.207,208.207,208.u0" +
                ",36g.1tk,6tk.1p4,2j4.1p3,1p4.1xz,1y0.3e7,3e8.sw,4yw.2cf,2cg.18f,18g.1nz,1o0.1g8,280.1z3,1z4.1tj," +
                "1tk.1un,1uo.1sf,1sg.167,168.37j,37k.1q7,1q8.2vb,2vc.2a7,2a8.1lr,1ls.2qv,2qw.13z,140.1f3,1f4.1br," +
                "1bs.1lr,1ls.10n,10o.1rb,1rc.347,348.12v,12w.sw,1tk.1lr,1ls.2bb,2bc.153,154.1if,1ig.2wf,2wg.1q7,1" +
                "q8.19j,19k.167,168.1lr,1ls.1if,1ig.153,154.2mg,3pc.1sf,1sg.1kn,1ko.2gv,2gw.13z,140.1dz,1e0.2bc,5" +
                "rs.13z,140.11r,11s.1cv,1cw.23k,36g.1wv,1ww.v3,v4.w8,1y0.1dz,1e0.11r,11s.2pr,2ps.xc,1uo.38n,38o.3" +
                "n3,3n4.2xj,2xk.2vb,2vc.25r,25s.2k7,2k8.sw,30w.2k7,2k8.sw,1ko.1tj,1tk.2k7,2k8.zj,zk.1rb,1rc.1dz,1" +
                "e0.12v,12w.4yw,5w8.2hz,2i0.xc,280.1hc,2lc.sw,5m8.11s,afk.1br,1bs.2hz,2i0.1uo,2nk.1g7,1g8.26w,460" +
                ".yf,yg.27z,280.1nz,1o0.v4,4ns.zk,3w0.sw,1ko.1mv,1mw.1an,1ao.1q7,1q8.1vr,1vs.17c,21c.2xj,2xk.1if," +
                "1ig.24n,24o.207,208.1g7,1g8.1q7,1q8.1kn,1ko.2dj,2dk.2yn,2yo.1xz,1y0.22g,3kw.1p3,1p4.sv,sw.1rb,1r" +
                "c.1mw,2xk.2rz,2s0.26v,26w.1sg,334.2pr,2ps.yf,yg.w7,w8.1ko,2s0.1ig,2k8.w7,w8.140,26w.168,2s0.19j," +
                "19k.23j,23k.293,294.11s,a4g.1wv,1ww.1if,1ig.yf,yg.2a8,6jk.10o,8js.293,294.17b,17c.1hb,1hc.1z3,1z" +
                "4.w7,w8.2fr,2fs.1sf,1sg.13z,140.1nz,1o0.2zr,2zs.sw,1mw.2en,2eo.2dj,2dk.u0,1uo.26v,26w.168,25s.19" +
                "j,19k.1q7,1q8.1uo,3c0.1tj,1tk.38n,38o.2cf,2cg.3av,3aw.1p4,2t4.19k,2a8.154,294.3w0,4yw.1br,1bs.1k" +
                "n,1ko.sw,4yw.zk,26w.2i0,l2w.1sg,5fk.167,168.1an,1ao.2eo,ac8.2a7,2a8.25r,25s.1mv,1mw.1e0,26w.1hb," +
                "1hc.1e0,54g.zk,2u8.sw,2s0.1br,1bs.1dz,1e0.22g,30w.26v,26w.xc,1uo.1jk,2gw.1z3,1z4.333,334.1if,1ig" +
                ".1xz,1y0.167,168.25s,3aw.154,460.18g,6mw.1bs,4g0.153,154.1ig,3e8.sw,22g.xc,960.1hb,1hc.11r,11s.1" +
                "7c,24o.2t3,2t4.1f3,1f4.347,348.sw,ens.27z,280.1wv,1ww.1if,1ig.1tj,1tk.1bs,3w0.24n,24o.2pr,2ps.1m" +
                "v,1mw.v3,v4.1lr,1ls.207,208.2dk,42o.1br,1bs.1xz,1y0.1cv,1cw.21b,21c.1an,1ao.1hb,1hc.294,3rk.2zr," +
                "2zs.2a7,2a8.2gw,4q0.293,294.1rb,1rc.xc,1tk.2pr,2ps.26v,26w.1g7,1g8.1ao,2bc.1jj,1jk.1tj,1tk.22f,2" +
                "2g.25r,25s.1nz,1o0.1p4,36g.1ls,3n4.2t3,2t4.2xk,40g.1un,1uo.10n,10o.1ww,2yo.2rz,2s0.2yn,2yo.2rz,2" +
                "s0.25s,38o.2rz,2s0.1kn,1ko.22f,22g.zj,zk.1nz,1o0.2a7,2a8.153,154.1mv,1mw.2fr,2fs.3sn,3so.1vr,1vs" +
                ".1sf,1sg.1kn,1ko.21b,21c.1f3,1f4.1rb,1rc.21b,21c.1hb,1hc.2wf,2wg.1hb,1hc.19j,19k.1q7,1q8.1wv,1ww" +
                ".1f3,1f4.1hb,1hc.1sg,348.1nz,1o0.1kn,1ko.2fr,2fs.1hb,1hc.1g7,1g8.v4,3y8.v4,i20.23j,23k.1p3,1p4.2" +
                "a7,2a8.zk,39s.207,208.sw,1o0.1p3,1p4.1an,1ao.1hb,1hc.2gw,7dk.2pr,2ps.18f,18g.167,168.1z3,1z4.1z3" +
                ",1z4.153,154.1tj,1tk.23j,23k.2nj,2nk.2en,2eo.2a7,2a8.2bc,44w.2dk,3qg.sw,2wg.sw,aog.10o,7vc.sw,29" +
                "4.18g,3js.208,4g0.1dz,1e0.u0,4co.1e0,2vc.23k,6ko.sw,4ag.1lr,1ls.320,3ts.2pr,2ps.35b,35c.2a7,2a8." +
                "2en,2eo.1ko,72g.2a7,2a8.4mo,5l4.1p3,1p4.13z,140.3qf,3qg.1un,1uo.154,2j4.3jr,3js.293,294.1f4,2nk." +
                "2mf,2mg.1un,1uo.153,154.1z3,1z4.1g7,1g8.12w,3aw.3hk,4ug.sw,474.1lr,1ls.3sn,3so.18f,18g.4ev,4ew.1" +
                "nz,1o0.2k7,2k8.1q7,1q8.154,4r4.22f,22g.2qv,2qw.168,2yo.sw,25s.4ds,6o0.12v,12w.11r,11s.1vr,1vs.1y" +
                "0,4vk.21b,21c.13z,140.2hz,2i0.4af,4ag.11r,11s.2lb,2lc.2ps,86g.2wf,2wg.30v,30w.1f4,2dk.2k7,2k8.1f" +
                "3,1f4.2j3,2j4.19j,19k.w8,3w0.1an,1ao.17c,3ts.1tk,4s8.2cg,3kw.2dj,2dk.24n,24o.1rc,f4g.1dz,1e0.1g8" +
                ",2bc.2pr,2ps.1tj,1tk.2k7,2k8.22f,22g.2cg,3w0.tz,u0.sw,os8.1hb,1hc.1un,1uo.320,3w0.293,294.25r,25" +
                "s.yf,yg.3in,3io.2lb,2lc.1q7,1q8.1un,1uo.1xz,1y0.sw,1sg.167,168.sw,26w.3n3,3n4.1if,1ig.2zr,2zs.25" +
                "r,25s.1vr,1vs.24n,24o.2u7,2u8.25r,25s.2t4,4mo.13z,140.1sg,4s8.10n,10o.1dz,1e0.168,av4.zj,zk.1q8," +
                "38o.2bc,3x4.2cf,2cg.37j,37k.3sn,3so.2gv,2gw.1z3,1z4.17b,17c.1tj,1tk.2k7,2k8.2nj,2nk.1if,1ig.1vr," +
                "1vs.37j,37k.21b,21c.1vr,1vs.1jj,1jk.1lr,1ls.21b,21c.11s,2u8.1dz,1e0.23j,23k.26v,26w.1p3,1p4.1mv," +
                "1mw.2on,2oo.w8,280.334,42o.30v,30w.sw,1ww.1br,1bs.37k,c3k.1z3,1z4.1g7,1g8.1nz,1o0.11r,11s.19j,19" +
                "k.1hb,1hc.23j,23k.2t3,2t4.1kn,1ko.1an,1ao.2t3,2t4.26v,26w.2on,2oo.1jj,1jk.3jr,3js.36f,36g.2j3,2j" +
                "4.3d3,3d4.24n,24o.24n,24o.xb,xc.25r,25s.yf,yg.1an,1ao.2oo,54g.1br,1bs.2gw,71c.2ps,3js.11r,11s.yg" +
                ",2bc.2mg,10ao.17b,17c.1an,1ao.1lr,1ls.2cf,2cg.1rb,1rc.1xz,1y0.2dj,2dk.2u7,2u8.293,294.1ls,3rk.2g" +
                "w,3c0.2eo,3fc.347,348.140,gg8.2en,2eo.2rz,2s0.2mf,2mg.2nk,8kw.21b,21c.1z3,1z4.2qv,2qw.3in,3io.2f" +
                "s,3e8.19j,19k.167,168.1o0,2i0.1un,1uo.yf,yg.xb,xc.1ww,5og.26v,26w.2t3,2t4.1vr,1vs.1hb,1hc.4af,4a" +
                "g.294,3so.1xz,1y0.1bs,2qw.sw,1o0.11r,11s.sw,22g.11s,24o.11s,2a8.1z3,1z4.1f3,1f4.sw,1z4.v4,39s.10" +
                "n,10o.26v,26w.1kn,1ko.2fr,2fs.2en,2eo.35b,35c.1f4,334.1hb,1hc.11r,11s.18g,2fs.2i0,4kg.1z3,1z4.v4" +
                ",488.2mf,2mg.1cw,294.19k,4bk.25r,25s.19k,2cg.1un,1uo.13z,140.1o0,3n4.167,168.1mv,1mw.1p3,1p4.320" +
                ",474.24n,24o.1lr,1ls.1an,1ao.2lb,2lc.1jj,1jk.11s,3hk.2nj,2nk.18f,18g.1jj,1jk.1kn,1ko.293,294.30w" +
                ",4r4.12w,2qw.1ls,36g.13z,140.1xz,1y0.21b,21c.167,168.1tj,1tk.w8,1sg.1g7,1g8.1f3,1f4.3n3,3n4.yg,2" +
                "yo.154,2j4.1mv,1mw.208,3rk.1p4,3ts.11r,11s.2bb,2bc.22f,22g.2k8,5a0.2en,2eo.sw,2s0.19k,2t4.1kn,1k" +
                "o.1q8,2j4.yf,yg.154,39s.1g7,1g8.1an,1ao.1sf,1sg.140,21c.xc,1tk.1q8,2vc.1ls,3m0.sw,1mw.1nz,1o0.10" +
                "n,10o.1dz,1e0.167,168.1mv,1mw.sw,23k.sw,38o.1vr,1vs.1an,1ao.31z,320.12v,12w.1xz,1y0.27z,280.17b," +
                "17c.1br,1bs.1dz,1e0.1e0,3aw.1q7,1q8.1mw,2u8.1p3,1p4.19k,21c.10o,3kw.v3,v4.2en,2eo.23k,4ew.153,15" +
                "4.27z,280.1an,1ao.1tj,1tk.37k,4i8.1nz,1o0.1kn,1ko.v4,1ww.2t3,2t4.17b,17c.w8,2t4.168,2k8.11r,11s." +
                "2vc,5zk.10o,c94.18f,18g.17b,17c.xb,xc.1tj,1tk.21b,21c.25r,25s.2ps,42o.1f3,1f4.1ig,3js.10n,10o.16" +
                "8,4xs.w8,294.v3,v4.17c,2dk.sw,2i0.sw,2xk.sw,2dk.11r,11s.1br,1bs.1lr,1ls.21b,21c.1nz,1o0.1g7,1g8." +
                "2bb,2bc.26v,26w.w7,w8.1vr,1vs.xb,xc.1lr,1ls.1jj,1jk.sw,1mw.1dz,1e0.21b,21c.zj,zk.207,208.13z,140" +
                ".1if,1ig.sv,sw.zk,4wo.sw,1ko.xb,xc.tz,u0.1g7,1g8.12v,12w.2bb,2bc.1cv,1cw.1un,1uo.1f3,1f4.12v,12w" +
                ".21b,21c.1kn,1ko.293,294.2u7,2u8.3d3,3d4.tz,u0.1mv,1mw.13z,140.w8,4ns.21b,21c.208,30w.10o,1y0.18" +
                "f,18g.yg,3e8.1g8,4ew.sw,26w.153,154.1an,1ao.1f3,1f4.1z3,1z4.1f4,76w.1o0,334.1g8,9i8.1sg,4jc.1hc," +
                "54g.1ls,69k.10n,10o.1mv,1mw.168,208.12v,12w.1p3,1p4.1br,1bs.1q8,30w.1mv,1mw.2zr,2zs.1kn,1ko.13z," +
                "140.333,334.1if,1ig.10n,10o.10n,10o.3kv,3kw.1z3,1z4.2on,2oo.13z,140.2en,2eo.3d4"
    }
}
