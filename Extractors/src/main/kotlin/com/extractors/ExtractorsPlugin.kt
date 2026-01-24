package com.extractors

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class ExtractorsPlugin : BasePlugin() {
    override fun load() {
        // تسجيل جميع الاستخراجات الأساسية
        registerExtractorAPI(GoStream())
        registerExtractorAPI(Govad())
        registerExtractorAPI(JWPlayer())
        registerExtractorAPI(LinkBox())
        registerExtractorAPI(Moshahda())
        registerExtractorAPI(MyVid())
        registerExtractorAPI(VidHD())
        registerExtractorAPI(Vidmoly())
        registerExtractorAPI(VoeSx())
        
        // تسجيل استخراجات StreamTape
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(StreamTapeNet())
        registerExtractorAPI(StreamTapeCom())
        registerExtractorAPI(StreamTapeTo())
        
        // تسجيل استخراجات DoodStream
        registerExtractorAPI(DoodStream())
        registerExtractorAPI(DoodWs())
        registerExtractorAPI(DoodTo())
        registerExtractorAPI(DoodWatch())
        
        // تسجيل استخراجات MixDrop
        registerExtractorAPI(MixDrop())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(MixDropTo())
        registerExtractorAPI(MixDropCh())
        
        // تسجيل استخراجات MegaUp
        registerExtractorAPI(MegaUp())
        registerExtractorAPI(MegaUpCo())
        registerExtractorAPI(MegaUpIo())
        
        // تسجيل استخراجات FileMoon
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(FileMoonTo())
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(FileMoonNl())
        
        // تسجيل استخراجات VidGuard
        registerExtractorAPI(VidGuard())
        registerExtractorAPI(VidGuardTo())
        registerExtractorAPI(VidGuardVp())
        registerExtractorAPI(VidGuardIo())
        
        // تسجيل استخراجات PixelDrain
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(PixelDrainCom())
        
        // تسجيل استخراجات KrakenFiles
        registerExtractorAPI(KrakenFiles())
        registerExtractorAPI(KrakenFilesCom())
        
        // تسجيل استخراجات BayFiles
        registerExtractorAPI(BayFiles())
        registerExtractorAPI(BayFilesCom())
        registerExtractorAPI(BayFilesVn())
        
        // تسجيل استخراجات Uptobox
        registerExtractorAPI(Uptobox())
        registerExtractorAPI(UptoboxCom())
        registerExtractorAPI(UptoboxNl())
        
        // تسجيل استخراجات 1Fichier
        registerExtractorAPI(Fichier())
        registerExtractorAPI(FichierCom())
        registerExtractorAPI(FichierOrg())
        
        // تسجيل استخراجات MediaFire
        registerExtractorAPI(MediaFire())
        registerExtractorAPI(MediaFireCom())
        
        // تسجيل استخراجات Zippyshare
        registerExtractorAPI(Zippyshare())
        registerExtractorAPI(ZippyshareCom())
    }
}
