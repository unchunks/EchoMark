package com.unchunks.echomark.data.extract.image

/**
 * ML Kit Image Labeling(既定モデル)の英語ラベルを日本語にする。
 * 本文に日本語で入れておくと、キーワード検索(本文の部分一致)やタグ付けで日本語の言葉として使える。
 * 表に無いラベルは英語のまま使う(AI は英語でも理解できる)。手がかりにならないラベルは捨てる。
 * ラベル一覧: https://developers.google.com/ml-kit/vision/image-labeling/label-map
 */
object ImageLabelsJa {

    /** 1枚の画像に添えるラベルの最大数 */
    const val MAX_LABELS = 6

    /**
     * 確からしい順の英語ラベルを、日本語にして重複と手がかりにならないものを除き、[MAX_LABELS] 件までにする。
     */
    fun translate(labels: List<String>): List<String> = labels
        .filterNot { it in IGNORED }
        .map { TABLE[it] ?: it }
        .distinct()
        .take(MAX_LABELS)

    /** 写っているものの手がかりにならない(姿勢・雰囲気・曖昧な)ラベル */
    private val IGNORED = setOf(
        "Sitting", "Standing", "Interaction", "Leisure", "Event", "Fun", "Cool", "Dude", "Flesh", "Skin",
        "Musical", "Mufti", "Junk", "Flap", "Bangs", "Eyelash", "Love", "Pattern", "Monochrome", "Product",
        "Model", "Balance", "Strap", "Pocket", "Toe", "Trunk", "Militia", "Steaming", "Glitter", "Infrastructure"
    )

    private val TABLE = mapOf(
        // 人・体
        "Person" to "人", "Team" to "チーム", "Crowd" to "人混み", "Baby" to "赤ちゃん", "Smile" to "笑顔",
        "Laugh" to "笑い", "Selfie" to "自撮り", "Beard" to "ひげ", "Moustache" to "口ひげ", "Hair" to "髪",
        "Hand" to "手", "Foot" to "足", "Ear" to "耳", "Mouth" to "口", "Muscle" to "筋肉", "Tattoo" to "タトゥー",
        "Grandparent" to "祖父母", "Bride" to "花嫁", "Groom" to "花婿", "Marriage" to "結婚式", "Crew" to "クルー",
        "Community" to "コミュニティ", "Musician" to "ミュージシャン", "Singer" to "歌手", "Clown" to "ピエロ",
        // 食べ物・飲み物
        "Food" to "食べ物", "Cuisine" to "料理", "Meal" to "食事", "Lunch" to "昼食", "Supper" to "夕食",
        "Bento" to "弁当", "Sushi" to "寿司", "Pizza" to "ピザ", "Cheeseburger" to "チーズバーガー",
        "Hot dog" to "ホットドッグ", "Fast food" to "ファストフード", "Bread" to "パン", "Cake" to "ケーキ",
        "Cookie" to "クッキー", "Pie" to "パイ", "Gelato" to "ジェラート", "Icing" to "アイシング",
        "Fruit" to "果物", "Vegetable" to "野菜", "Pho" to "フォー", "Couscous" to "クスクス",
        "Coffee" to "コーヒー", "Cappuccino" to "カプチーノ", "Juice" to "ジュース", "Cola" to "コーラ",
        "Wine" to "ワイン", "Alcohol" to "お酒", "Eating" to "食事中", "Picnic" to "ピクニック", "Menu" to "メニュー",
        "Tableware" to "食器", "Cutlery" to "カトラリー", "Cookware and bakeware" to "調理器具", "Cup" to "カップ",
        "Saucer" to "ソーサー",
        // 動物
        "Dog" to "犬", "Cat" to "猫", "Pet" to "ペット", "Bird" to "鳥", "Horse" to "馬", "Cattle" to "牛",
        "Bull" to "雄牛", "Bear" to "クマ", "Duck" to "アヒル", "Penguin" to "ペンギン", "Turtle" to "カメ",
        "Crocodile" to "ワニ", "Butterfly" to "蝶", "Insect" to "昆虫", "Dinosaur" to "恐竜", "Seal" to "アザラシ",
        "Herd" to "群れ", "Waterfowl" to "水鳥", "Aquarium" to "水族館", "Dragon" to "ドラゴン",
        // 自然・景色
        "Sky" to "空", "Sunset" to "夕焼け", "Mountain" to "山", "Beach" to "ビーチ", "Sand" to "砂",
        "Lake" to "湖", "River" to "川", "Waterfall" to "滝", "Forest" to "森", "Jungle" to "ジャングル",
        "Garden" to "庭", "Park" to "公園", "Field" to "野原", "Flower" to "花", "Petal" to "花びら",
        "Plant" to "植物", "Flora" to "草花", "Branch" to "枝", "Twig" to "小枝", "Rock" to "岩", "Cliff" to "崖",
        "Cave" to "洞窟", "Canyon" to "峡谷", "Desert" to "砂漠", "Dune" to "砂丘", "Glacier" to "氷河",
        "Iceberg" to "氷山", "Ice" to "氷", "Icicle" to "つらら", "Volcano" to "火山", "Swamp" to "沼地",
        "Prairie" to "草原", "Reef" to "サンゴ礁", "Underwater" to "水中", "Fog" to "霧", "Storm" to "嵐",
        "Lightning" to "雷", "Rainbow" to "虹", "Aurora" to "オーロラ", "Moon" to "月", "Star" to "星",
        "Nebula" to "星雲", "Comet" to "彗星", "Space" to "宇宙", "Fire" to "火", "Bonfire" to "たき火",
        "Fireworks" to "花火", "Sparkler" to "線香花火", "Soil" to "土", "Farm" to "農場", "Ranch" to "牧場",
        // 建物・場所
        "Building" to "建物", "Skyscraper" to "高層ビル", "Skyline" to "街並み", "Tower" to "塔", "Bridge" to "橋",
        "Castle" to "城", "Palace" to "宮殿", "Temple" to "寺院", "Church" to "教会", "Cathedral" to "大聖堂",
        "Mosque" to "モスク", "Monument" to "記念碑", "Statue" to "像", "Ruins" to "遺跡", "Museum" to "博物館",
        "Stadium" to "スタジアム", "Factory" to "工場", "Barn" to "納屋", "Lighthouse" to "灯台", "Pier" to "桟橋",
        "Dam" to "ダム", "Road" to "道路", "Asphalt" to "アスファルト", "Stairs" to "階段", "Roof" to "屋根",
        "Wall" to "壁", "Brick" to "レンガ", "Tile" to "タイル", "Playground" to "遊び場", "Construction" to "工事",
        "Kitchen" to "キッチン", "Bedroom" to "寝室", "Bathroom" to "浴室", "Room" to "部屋", "Ballroom" to "ホール",
        "Bar" to "バー", "Nightclub" to "ナイトクラブ", "Casino" to "カジノ", "School" to "学校", "Class" to "授業",
        "Countertop" to "カウンター",
        // 乗り物
        "Car" to "車", "Vehicle" to "乗り物", "Bus" to "バス", "Train" to "電車", "Bicycle" to "自転車",
        "Motorcycle" to "バイク", "Airplane" to "飛行機", "Airliner" to "旅客機", "Aircraft" to "航空機",
        "Helicopter" to "ヘリコプター", "Rocket" to "ロケット", "Boat" to "ボート", "Sailboat" to "ヨット",
        "Speedboat" to "モーターボート", "Canoe" to "カヌー", "Kayak" to "カヤック", "Submarine" to "潜水艦",
        "Shipwreck" to "難破船", "Tractor" to "トラクター", "Van" to "バン", "Tire" to "タイヤ", "Wheel" to "車輪",
        "Rickshaw" to "人力車", "Aviation" to "航空",
        // 物
        "Computer" to "コンピューター", "Mobile phone" to "携帯電話", "Television" to "テレビ",
        "Screenshot" to "スクリーンショット", "Web page" to "Web ページ", "Icon" to "アイコン", "Poster" to "ポスター",
        "Comics" to "漫画", "Newspaper" to "新聞", "News" to "ニュース", "Paper" to "紙", "Receipt" to "レシート",
        "Passport" to "パスポート", "Money" to "お金", "Whiteboard" to "ホワイトボード", "Blackboard" to "黒板",
        "Presentation" to "プレゼンテーション", "Desk" to "机", "Chair" to "椅子", "Couch" to "ソファ",
        "Bench" to "ベンチ", "Shelf" to "棚", "Drawer" to "引き出し", "Cabinetry" to "戸棚", "Clock" to "時計",
        "Toy" to "おもちゃ", "Stuffed toy" to "ぬいぐるみ", "Plush" to "ぬいぐるみ", "Lego" to "レゴ",
        "Balloon" to "風船", "Flag" to "旗", "Umbrella" to "傘", "Bag" to "かばん", "Handbag" to "ハンドバッグ",
        "Musical instrument" to "楽器", "Piano" to "ピアノ", "Glasses" to "眼鏡", "Sunglasses" to "サングラス",
        "Helmet" to "ヘルメット", "Hat" to "帽子", "Cap" to "キャップ", "Shoe" to "靴", "Sneakers" to "スニーカー",
        "Dress" to "ドレス", "Jacket" to "ジャケット", "Jeans" to "ジーンズ", "Tie" to "ネクタイ", "Scarf" to "スカーフ",
        "Necklace" to "ネックレス", "Ring" to "指輪", "Jewellery" to "アクセサリー", "Lipstick" to "口紅",
        "Textile" to "布", "Pillow" to "枕", "Curtain" to "カーテン", "Flowerpot" to "植木鉢", "Pool" to "プール",
        // 行事・活動
        "Christmas" to "クリスマス", "Santa claus" to "サンタクロース", "Party" to "パーティー",
        "Concert" to "コンサート", "Graduation" to "卒業式", "Vacation" to "休暇", "Camping" to "キャンプ",
        "Carnival" to "カーニバル", "Dance" to "ダンス", "Sports" to "スポーツ", "Soccer" to "サッカー",
        "Rugby" to "ラグビー", "Swimming" to "水泳", "Running" to "ランニング", "Marathon" to "マラソン",
        "Cycling" to "サイクリング", "Skiing" to "スキー", "Surfing" to "サーフィン", "Fishing" to "釣り",
        "Competition" to "競技", "Racing" to "レース", "Gymnastics" to "体操", "Badminton" to "バドミントン",
        "Sleep" to "睡眠", "Knitting" to "編み物", "Needlework" to "手芸", "Watercolor paint" to "水彩画",
        "Fiction" to "フィクション", "Superhero" to "スーパーヒーロー"
    )
}
