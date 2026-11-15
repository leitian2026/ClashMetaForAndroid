package com.github.kr328.clash.design.custom

// CUSTOM (CUSTOM.md #54, not upstream): Chinese names of Cloudflare data centre codes (IATA airport codes), shown in
// brackets next to the code in the test-IP table. Built in, because the list of data centres from the network is only
// in English and sometimes cannot be fetched at all (HTTP 403). A code that is not here keeps whatever the network
// list says, or is shown as the bare code.
object ColoNames {
    fun cn(code: String): String? = names[code.uppercase()]

    private val names: Map<String, String> = HashMap<String, String>().apply {
        fun add(vararg pairs: Pair<String, String>) = pairs.forEach { put(it.first, it.second) }

        // North America
        add(
            "ATL" to "亚特兰大", "BOS" to "波士顿", "BUF" to "布法罗", "CLT" to "夏洛特", "ORD" to "芝加哥",
            "CMH" to "哥伦布", "DFW" to "达拉斯", "DEN" to "丹佛", "DTW" to "底特律", "HNL" to "檀香山",
            "IAH" to "休斯顿", "IND" to "印第安纳波利斯", "JAX" to "杰克逊维尔", "MCI" to "堪萨斯城",
            "LAS" to "拉斯维加斯", "LAX" to "洛杉矶", "MFE" to "麦卡伦", "MEM" to "孟菲斯", "MIA" to "迈阿密",
            "MSP" to "明尼阿波利斯", "BNA" to "纳什维尔", "EWR" to "纽瓦克", "JFK" to "纽约", "LGA" to "纽约",
            "ORF" to "诺福克", "OMA" to "奥马哈", "PHL" to "费城", "PHX" to "凤凰城", "PIT" to "匹兹堡",
            "PDX" to "波特兰", "RIC" to "里士满", "SMF" to "萨克拉门托", "SLC" to "盐湖城", "SAN" to "圣地亚哥·美国",
            "SJC" to "圣何塞", "SFO" to "旧金山", "SEA" to "西雅图", "STL" to "圣路易斯", "TPA" to "坦帕",
            "IAD" to "华盛顿", "DCA" to "华盛顿", "ABQ" to "阿尔伯克基", "ANC" to "安克雷奇", "BGR" to "班戈",
            "BTV" to "伯灵顿", "CHS" to "查尔斯顿", "CVG" to "辛辛那提", "GSP" to "格林维尔", "MGM" to "蒙哥马利",
            "LIT" to "小石城", "OKC" to "俄克拉何马城", "TUL" to "塔尔萨", "ICT" to "威奇托", "DSM" to "得梅因",
            "MSN" to "麦迪逊", "MKE" to "密尔沃基", "ALB" to "奥尔巴尼", "SYR" to "锡拉丘兹", "ROC" to "罗切斯特",
            "BDL" to "哈特福德", "PVD" to "普罗维登斯", "PWM" to "波特兰·缅因", "MHT" to "曼彻斯特·美国",
            "RDU" to "罗利", "GSO" to "格林斯伯勒", "CHO" to "夏洛茨维尔", "TLH" to "塔拉哈西", "MCO" to "奥兰多",
            "ELP" to "埃尔帕索", "SAT" to "圣安东尼奥", "AUS" to "奥斯汀", "BOI" to "博伊西", "GEG" to "斯波坎",
            "RNO" to "里诺", "FSD" to "苏福尔斯", "FAR" to "法戈", "LNK" to "林肯",
            "YYZ" to "多伦多", "YUL" to "蒙特利尔", "YVR" to "温哥华", "YYC" to "卡尔加里", "YOW" to "渥太华",
            "YWG" to "温尼伯", "YXE" to "萨斯卡通", "YQT" to "雷湾", "YHZ" to "哈利法克斯", "YXX" to "阿伯茨福德",
            "QRO" to "克雷塔罗", "MEX" to "墨西哥城", "GDL" to "瓜达拉哈拉", "GUA" to "危地马拉城",
            "PTY" to "巴拿马城", "SJO" to "圣何塞·哥斯达黎加", "SJU" to "圣胡安", "KIN" to "金斯敦",
            "SDQ" to "圣多明各", "PAP" to "太子港", "BZE" to "伯利兹城", "TGU" to "特古西加尔巴", "SAL" to "圣萨尔瓦多",
            "MTY" to "蒙特雷", "CUN" to "坎昆", "BGI" to "布里奇敦", "POS" to "西班牙港", "NAS" to "拿骚",
        )

        // Europe
        add(
            "AMS" to "阿姆斯特丹", "ARN" to "斯德哥尔摩", "ATH" to "雅典", "BCN" to "巴塞罗那", "BEG" to "贝尔格莱德",
            "BRU" to "布鲁塞尔", "BUD" to "布达佩斯", "BTS" to "布拉迪斯拉发", "CDG" to "巴黎", "CPH" to "哥本哈根",
            "DUB" to "都柏林", "DUS" to "杜塞尔多夫", "EDI" to "爱丁堡", "FRA" to "法兰克福", "GVA" to "日内瓦",
            "GOT" to "哥德堡", "HAM" to "汉堡", "HEL" to "赫尔辛基", "IST" to "伊斯坦布尔", "KBP" to "基辅",
            "LIS" to "里斯本", "LHR" to "伦敦", "LUX" to "卢森堡", "MAD" to "马德里", "MAN" to "曼彻斯特·英国",
            "MRS" to "马赛", "MXP" to "米兰", "MUC" to "慕尼黑", "OSL" to "奥斯陆", "OTP" to "布加勒斯特",
            "PRG" to "布拉格", "RIX" to "里加", "SOF" to "索非亚", "STR" to "斯图加特", "TLL" to "塔林",
            "VIE" to "维也纳", "VNO" to "维尔纽斯", "WAW" to "华沙", "ZAG" to "萨格勒布", "ZRH" to "苏黎世",
            "BLQ" to "博洛尼亚", "FCO" to "罗马", "BER" to "柏林", "TXL" to "柏林", "LCA" to "拉纳卡",
            "KEF" to "雷克雅未克", "BGO" to "卑尔根", "NAP" to "那不勒斯", "PMO" to "巴勒莫", "TLS" to "图卢兹",
            "LYS" to "里昂", "BOD" to "波尔多", "NCE" to "尼斯", "ORK" to "科克", "BHX" to "伯明翰", "GLA" to "格拉斯哥",
            "BFS" to "贝尔法斯特", "CWL" to "卡迪夫", "LPA" to "拉斯帕尔马斯", "VLC" to "瓦伦西亚", "AGP" to "马拉加",
            "KIV" to "基希讷乌", "TIA" to "地拉那", "SKP" to "斯科普里", "SJJ" to "萨拉热窝", "LJU" to "卢布尔雅那",
            "MLA" to "马耳他", "NUE" to "纽伦堡", "LEJ" to "莱比锡", "HAJ" to "汉诺威", "CGN" to "科隆",
            "DME" to "莫斯科", "SVO" to "莫斯科", "LED" to "圣彼得堡", "SVX" to "叶卡捷琳堡", "KJA" to "克拉斯诺亚尔斯克",
            "VVO" to "符拉迪沃斯托克", "OVB" to "新西伯利亚", "KZN" to "喀山", "MSQ" to "明斯克", "BLL" to "比隆",
            "TRN" to "都灵", "VCE" to "威尼斯", "TGD" to "波德戈里察",
            "PRN" to "普里什蒂纳", "OPO" to "波尔图", "CLJ" to "克卢日", "KRK" to "克拉科夫", "POZ" to "波兹南",
            "GDN" to "格但斯克", "SZG" to "萨尔茨堡", "SKG" to "塞萨洛尼基",
        )

        // Asia and the Middle East
        add(
            "HKG" to "香港", "MFM" to "澳门", "TPE" to "台北", "KHH" to "高雄", "NRT" to "东京", "HND" to "东京",
            "KIX" to "大阪", "ITM" to "大阪", "FUK" to "福冈", "OKA" to "那霸", "ICN" to "首尔", "PUS" to "釜山",
            "SIN" to "新加坡", "KUL" to "吉隆坡", "BKK" to "曼谷", "CGK" to "雅加达", "MNL" to "马尼拉", "CEB" to "宿务",
            "SGN" to "胡志明市", "HAN" to "河内", "DAD" to "岘港", "PNH" to "金边", "RGN" to "仰光", "DAC" to "达卡",
            "CGP" to "吉大港", "DEL" to "新德里", "BOM" to "孟买", "BLR" to "班加罗尔", "MAA" to "金奈", "HYD" to "海得拉巴",
            "CCU" to "加尔各答", "AMD" to "艾哈迈达巴德", "COK" to "科钦", "CCJ" to "卡利卡特", "JAI" to "斋浦尔",
            "PNQ" to "浦那", "NAG" to "那格浦尔", "IXC" to "昌迪加尔", "BBI" to "布巴内斯瓦尔", "GAU" to "古瓦哈提",
            "VTZ" to "维沙卡帕特南", "TRV" to "特里凡得琅", "KTM" to "加德满都", "CMB" to "科伦坡", "ISB" to "伊斯兰堡",
            "KHI" to "卡拉奇", "LHE" to "拉合尔", "DXB" to "迪拜", "AUH" to "阿布扎比", "DOH" to "多哈", "BAH" to "巴林",
            "KWI" to "科威特城", "MCT" to "马斯喀特", "AMM" to "安曼", "BEY" to "贝鲁特", "TLV" to "特拉维夫",
            "BGW" to "巴格达", "BSR" to "巴士拉", "EBL" to "埃尔比勒", "ISU" to "苏莱曼尼亚", "JED" to "吉达",
            "RUH" to "利雅得", "DMM" to "达曼", "TBS" to "第比利斯", "EVN" to "埃里温", "GYD" to "巴库",
            "ULN" to "乌兰巴托", "ALA" to "阿拉木图", "NQZ" to "阿斯塔纳", "FRU" to "比什凯克", "TAS" to "塔什干",
            "KHV" to "哈巴罗夫斯克", "NGO" to "名古屋", "CTS" to "札幌", "SDJ" to "仙台", "HIJ" to "广岛",
            "PEK" to "北京", "SHA" to "上海", "PVG" to "上海", "CAN" to "广州", "SZX" to "深圳", "CTU" to "成都",
            "CKG" to "重庆", "HGH" to "杭州", "NKG" to "南京", "WUH" to "武汉", "XIY" to "西安", "TSN" to "天津",
            "TAO" to "青岛", "DLC" to "大连", "FOC" to "福州", "KWE" to "贵阳", "CSX" to "长沙",
            "BWN" to "斯里巴加湾", "VTE" to "万象", "SUB" to "泗水", "DPS" to "巴厘岛", "MLE" to "马累", "JOG" to "日惹",
            "HAK" to "海口",
        )

        // Oceania
        add(
            "SYD" to "悉尼", "MEL" to "墨尔本", "BNE" to "布里斯班", "PER" to "珀斯", "ADL" to "阿德莱德",
            "CBR" to "堪培拉", "AKL" to "奥克兰", "WLG" to "惠灵顿", "CHC" to "基督城", "NOU" to "努美阿",
            "PPT" to "帕皮提", "GUM" to "关岛", "HBA" to "霍巴特", "DRW" to "达尔文", "CNS" to "凯恩斯",
        )

        // Africa
        add(
            "JNB" to "约翰内斯堡", "CPT" to "开普敦", "DUR" to "德班", "NBO" to "内罗毕", "LOS" to "拉各斯",
            "ACC" to "阿克拉", "CAI" to "开罗", "CMN" to "卡萨布兰卡", "ALG" to "阿尔及尔", "TUN" to "突尼斯",
            "DAR" to "达累斯萨拉姆", "EBB" to "恩德培", "KGL" to "基加利", "MPM" to "马普托", "LAD" to "罗安达",
            "MRU" to "路易港", "RUN" to "留尼汪", "TNR" to "塔那那利佛", "HRE" to "哈拉雷", "LUN" to "卢萨卡",
            "WDH" to "温得和克", "GBE" to "哈博罗内", "MBA" to "蒙巴萨", "DKR" to "达喀尔", "ABJ" to "阿比让",
            "DJI" to "吉布提", "ADD" to "亚的斯亚贝巴", "FIH" to "金沙萨", "BUQ" to "布拉瓦约", "BLZ" to "布兰太尔",
            "LLW" to "利隆圭", "ORN" to "奥兰", "TIP" to "的黎波里", "NIM" to "尼亚美", "OUA" to "瓦加杜古",
            "BKO" to "巴马科", "COO" to "科托努", "LFW" to "洛美", "CKY" to "科纳克里", "FNA" to "弗里敦",
            "PLZ" to "伊丽莎白港", "VFA" to "维多利亚瀑布",
        )

        // South America
        add(
            "GRU" to "圣保罗", "GIG" to "里约热内卢", "BSB" to "巴西利亚", "CWB" to "库里蒂巴", "POA" to "阿雷格里港",
            "FOR" to "福塔莱萨", "SSA" to "萨尔瓦多·巴西", "REC" to "累西腓", "BEL" to "贝伦", "MAO" to "玛瑙斯",
            "CNF" to "贝洛奥里藏特", "VCP" to "坎皮纳斯", "FLN" to "弗洛里亚诺波利斯", "GYN" to "戈亚尼亚",
            "EZE" to "布宜诺斯艾利斯", "COR" to "科尔多瓦", "SCL" to "圣地亚哥·智利", "LIM" to "利马",
            "BOG" to "波哥大", "MDE" to "麦德林", "UIO" to "基多", "GYE" to "瓜亚基尔", "ASU" to "亚松森",
            "MVD" to "蒙得维的亚", "CCS" to "加拉加斯", "LPB" to "拉巴斯", "VVI" to "圣克鲁斯", "CUR" to "库拉索",
            "PBM" to "帕拉马里博", "BAQ" to "巴兰基亚", "CLO" to "卡利", "GEO" to "乔治敦",
        )
    }
}
