# Guitar App build progress (for context recovery)
Notion hub page: https://app.notion.com/p/d1d39783ff8e4f78a500e042733caac3
Tasks DB: https://app.notion.com/p/0b3a87d2a25c475a82774b8380c2d733  data source: collection://d1a4141b-8c8b-4621-bec2-00b152cfbca0
Status options: 未開始 / 待你處理 (pending user) / 進行中 / 完成
Task index -> page id (https://app.notion.com/p/<id>):
0 rec test audio [PENDING] 15570f5357cd4cc7be1cafd4f69e0a5f
1 latency on phone [PENDING] f1e88830940947b482b30b5c1a2c5052
2 tuner YIN vs MPM 7e74004d206d4df88346a1c69426db00
3 onset tuning 4af93f2de3b44a5c80984ef39c623edf
4 chord check proto 70a4d1d6cd184c3a9164e7ac1fa591cb
5 strum dir classifier proto 794b754a64844bfe8bd88906733a6603
6 try basic pitch 9212482afb2a4ff180669fcd50484672
7 summarize thresholds 3dd5f03482834fc4a937a539681463fd
8 android project 1c14fd54f7e14bdba7423d5f4a64b5cd
9 capture pipeline e3e8abdc886140aca7f0175b62b062dc
10 pitch detect (C++->Kotlin) 682aee6475d74d13abe909edbfd5d941
11 tuner UI 0a952a9ae7f1406f9ea6dd068d3baa4b
12 latency calib screen 62284d8ea4f54603bac96be4917fb1e4
13 onset+chroma 5529f407ef3e432d86f2844b40983700
14 metronome+countin 5c7b0759ffec4eeeb6c868d5d94a26f2
15 chord library 7594d980117c40d19beaacdc88163b86
16 chord check mode 08bc6b9bc7644d71beeb03919441fb27
17 direction calib flow a93f9bf181534b379c2d088a45998c65
18 on-device dir classifier 8bb0fb3ecaba46ca8b9f7ebaa9a71537
19 pattern json + strum patterns 9036ecf4ae1a40e1ac2acd189a975f60
20 judge engine 8271ffeabdbb4171b74dc884ec915d42
21 game UI 3003d96ddf6d470c86a92a0fa9415457
22 difficulty setting 88b6d7aff8bf4d04ad150c7a7f21682b
23 practice features 5d82554b35f54daa9fe9437ea910f3d3
24 results + history 4d2c74ac7206465e8111f27ceb871ec2
25 MVP 1-week trial [PENDING] fdd280e9c9b54246adb26c545e1f7ad4
26 finger pattern format 550723d15dd845c99aab2f1868529d5b
27 8-10 finger patterns 508b1fa0c30240efa7d3db81e1b59462
28 tab UI 003086ff264842e6bd377674555fc7aa
29 note verification 7c2b3ed71ef54624b23aa8444ca185e8
30 basic pitch tflite 5703b7b6b8f345d18aa6d6842a9a73f2
31 finger judge 9b586ae5c5fb42b3b41e9600511824e7
32 finger accuracy test [PENDING] 8a8aaeec7a7f4696a751632d9f86ac30
33 PC server e31ed2fa25eb40c6ac2ac7688dbdf606
34 phone<->server network [PENDING] 632d3355f4674949a2848e5192822f7f
35 analysis pipeline a2b81bac32414fc5ba8034c7d8a22570
36 section detection 72e138159e934da1ac81572fd2a5ea41
37 chord simplify + capo 16400a0987d647dba7495c5669a60949
38 pattern suggestion engine 189b2969d92d42e6b459f64400de8c67
39 paste link + share intent 692ce05efe8e4b218bf5241ecd700b0e
40 chart preview + editor c7d4fdd4cc44488fb8c28507dabab3fb
41 play-along 7aa289af576444aabf142839c3e36e43
42 song library + cache f964b21b1ab840b7bf5d18dcefeca8ea
43 20-song eval [PENDING] 91e0049356fd44b798c84291c955023f
44 progress stats 15d7179a4e9c4627887d7f4e3e330e2a
45 practice tips ed74d49271bb4cb5a76723f2549d8735
46 levels/achievements 9c3358cf435240499a3912f8a1146d15
47 AEC 6b9a0366c4bb4fad8a81c261997aa258
48 smartwatch fallback ebad822fe1f64910aea209635f840538
49 on-device song analysis 1fb3ff83fb5844d7b572e2e03b30ab3d

## Done log
- v0.2.2: all flower emoji in the app replaced with music emoji (🌼 → 🎵/🎶, 🌷 → 🎧/🎶/🎤; tab「我的」+ avatar 🎧, week dots 🎵, level「練習生 🎵」); DESIGN.md「本星期音符」. Same signing key → installs over v0.2.1.
- v0.2.1 fix: app crashed when analysing a local song (old decoder kept the whole song at full rate + copies → OOM on long / hi-res files). Streaming decode + resample (10-min song ≈ 64 MB peak vs OOM before), app-scope SongImporter (one job, survives tab switches, cancel), 10-minute notice (status + song page), 300 MB / 10 s / no-chord guards, real file names as titles, crash log in Settings (share), largeHeap. +12 unit tests (StreamResampler, Pcm, DecodeMemoryTest in a 128 MB JVM), +4 screenshots.
- v0.2.0「晨光」UI/UX redesign (for beginner / girl guitarists, bright-morning feel): new palette + Nunito/Pacifico fonts, 4 tabs (今日/練習/歌曲/我的), beginner roadmap, daily goal + chord of the day + nickname greeting, restyled game lane/tuner/results/stats, custom slider + haptics, adaptive icon, local-day streak fix (Days.kt), 33 Paparazzi screenshot tests. See docs/DESIGN.md.
