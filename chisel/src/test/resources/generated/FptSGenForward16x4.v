/*
 * This file was generated using SGen v.26.7.15.
 *    _____ ______          SGen v.26.7.15 - A Generator of Streaming Hardware
 *   / ___// ____/__  ____  Department of Computer Science, ETH Zurich, Switzerland
 *   \__ \/ / __/ _ \/ __ \
 *  ___/ / /_/ /  __/ / / / Copyright (C) 2020-2025 François Serre (serref@inf.ethz.ch)
 * /____/\____/\___/_/ /_/  https://github.com/fserre/sgen
 *
 * This design operates on datasets of 16 elements, streamed over 4 cycles of 4 elements. This means that
 * each dataset has to be split into chunks of 4 elements that are input sequentially every cycle.
 * It has a latency of 41 cycles: the output will begin 41 cycles after the input has begun.
 * It supports full-throughput, which means that a new dataset may be input immediately after the previous one.
 * In total, this design can therefore perform a new transformation every 4 cycles.
 * As single RAM control is used, there must be precisely 4 cycles between datasets.
 * Otherwise, at least 4 additional cycles must be added to ensure that the first output dataset does not get corrupted.
 * Use the -dualRAMcontrol option to use dual control memory instead (use more resources).
 *
 * The interface works as follows:
 * - clk: The input clock, a cycle begins on ascending edge.
 * - reset: Reset signal. It has to be set to 1 during at least one cycle before the first input, and to 0 afterwards.
 * - next: Signals the arrival of the next dataset. It has to be set high 1 cycles before the first inputs enter, and left at 0 otherwise.
 *         Particularly, it should not be set to 1 more than once every 4 cycles, as it might leave the design in a
 *         confused state, that can only be recovered with a reset.
 * - next_out: Indicates that a new dataset begins to output.
 * - i0 - i3: The components of the chunks of the dataset. Each of these elements is a complex number in cartesian form (real and imaginary part are concatenated, each being a signed fixed-point number (18. 12 bits representation)).
 * - o0 - o3: The components of the chunks of the output dataset.
 *
 * If you would like to refer to this design, the following publications describe the methods used for its generation:
 * - Generator: F. Serre and M. Püschel, DSL-Based Hardware Generation with Scala: Example Fast Fourier Transforms and Sorting Networks, TRETS, 2019
 * - Streaming permutations: F. Serre, T. Holenstein and M. Püschel, Optimal Circuits for Streamed Linear Permutations using RAM, Proc. FPGA, pp. 215-223, 2016
 * - Algorithm folding: P. A. Milder, F. Franchetti, J. C. Hoe, and M. Püschel, Computer Generation of Hardware for Linear Digital Signal Processing Transforms, ACM TODAES, Vol. 17, No. 2, 2012
 * - Compact designs: F. Serre and M. Püschel, Memory-Efficient Fast Fourier Transform on Streaming Data by Fusing Permutations, Proc. FPGA, pp. 219-228, 2018
 * - Floating point arithmetic: F. de Dinechin and B. Pasca, Designing custom arithmetic data paths with FloPoCo, IEEE Design & Test of Computers, 28(4):18--27, 2011
 *
 */

module FptSGenForward(input clk,
  input reset,
  input next,
  input [59:0] i0,
  input [59:0] i1,
  input [59:0] i2,
  input [59:0] i3,
  output next_out,
  output [59:0] o0,
  output [59:0] o1,
  output [59:0] o2,
  output [59:0] o3);

  reg [29:0] s1;
  wire [29:0] s2;
  wire [29:0] s3;
  wire [29:0] s4;
  wire [59:0] s5;
  wire [29:0] s6;
  wire [29:0] s7;
  reg [29:0] s8;
  reg [29:0] s9;
  reg [29:0] s11;
  reg [29:0] s10;
  reg [29:0] s13;
  reg [29:0] s12;
  wire s14;
  wire [29:0] s15;
  wire [29:0] s16;
  reg [29:0] s17;
  reg [29:0] s18;
  reg [29:0] s19;
  wire [59:0] s20;
  wire [29:0] s21;
  wire [29:0] s22;
  reg [1:0] s24 [3:0];
  wire [1:0] s23;
  reg [29:0] s25;
  wire [59:0] s26;
  wire [29:0] s27;
  wire [29:0] s28;
  wire [29:0] s29;
  reg [29:0] s30;
  reg [1:0] s32 [18:0];
  wire [1:0] s31;
  reg [29:0] s34 [4:0];
  wire [29:0] s33;
  reg [29:0] s36 [4:0];
  wire [29:0] s35;
  wire [29:0] s37;
  wire [29:0] s38;
  wire [29:0] s39;
  wire [29:0] s40;
  reg [29:0] s41;
  reg [29:0] s42;
  reg [25:0] s43;
  reg [29:0] s44;
  reg s46 [8:0];
  wire s45;
  wire [29:0] s47;
  wire [29:0] s48;
  reg [29:0] s49;
  wire [29:0] s50;
  wire [29:0] s51;
  wire [29:0] s52;
  reg s54 [37:0];
  wire s53;
  reg [29:0] s55;
  reg [29:0] s56;
  wire [25:0] s57;
  reg [1:0] s59 [22:0];
  wire [1:0] s58;
  wire [29:0] s60;
  reg [29:0] s61;
  reg [29:0] s62;
  reg [29:0] s64 [2:0];
  wire [29:0] s63;
  reg [29:0] s65;
  reg [29:0] s66;
  reg s68;
  reg s67;
  reg [29:0] s69;
  reg [29:0] s70;
  reg [29:0] s71;
  reg [29:0] s72;
  wire [29:0] s73;
  wire [29:0] s74;
  reg [29:0] s75;
  reg [1:0] s76;
  wire [29:0] s77;
  reg [29:0] s78;
  reg [29:0] s79;
  reg s81 [3:0];
  wire s80;
  reg [29:0] s82;
  reg [29:0] s83;
  wire [29:0] s84;
  wire [29:0] s85;
  wire [29:0] s86;
  reg s88 [6:0];
  wire s87;
  wire [55:0] s89;
  wire [1:0] s90;
  reg s92 [6:0];
  wire s91;
  wire [29:0] s93;
  reg s95 [2:0];
  wire s94;
  wire [29:0] s96;
  wire [29:0] s97;
  wire [29:0] s98;
  wire [29:0] s99;
  reg [59:0] s100;
  wire [1:0] s101;
  wire [1:0] s102;
  wire [29:0] s103;
  reg [25:0] s104;
  reg [29:0] s105;
  reg [29:0] s106;
  reg [59:0] s107;
  reg [25:0] s108;
  wire [29:0] s109;
  wire [29:0] s110;
  reg [59:0] s112 [3:0]; // synthesis attribute ram_style of s112 is block
  reg [59:0] s111;
  reg [29:0] s113;
  wire [29:0] s114;
  wire [29:0] s115;
  wire [29:0] s116;
  reg [59:0] s118 [3:0]; // synthesis attribute ram_style of s118 is block
  reg [59:0] s117;
  reg [29:0] s120 [4:0];
  wire [29:0] s119;
  reg [29:0] s122 [4:0];
  wire [29:0] s121;
  reg [29:0] s123;
  reg [29:0] s125;
  reg [29:0] s124;
  reg [29:0] s127;
  reg [29:0] s126;
  wire [55:0] s128;
  wire [29:0] s129;
  wire [1:0] s130;
  reg [29:0] s131;
  wire [29:0] s132;
  wire [29:0] s133;
  wire [55:0] s134;
  reg [25:0] s135;
  wire [1:0] s136;
  wire [29:0] s137;
  reg [1:0] s139 [11:0];
  wire [1:0] s138;
  reg [29:0] s140;
  reg [29:0] s141;
  reg [29:0] s142;
  reg [29:0] s143;
  reg [59:0] s145 [3:0]; // synthesis attribute ram_style of s145 is block
  reg [59:0] s144;
  reg [29:0] s146;
  reg [29:0] s147;
  reg [29:0] s148;
  reg [29:0] s149;
  wire [29:0] s150;
  wire [55:0] s151;
  wire [29:0] s152;
  wire [29:0] s153;
  wire [29:0] s154;
  reg [29:0] s155;
  reg [29:0] s156;
  reg [29:0] s157;
  wire [29:0] s158;
  wire [29:0] s159;
  reg s161 [8:0];
  wire s160;
  reg [29:0] s162;
  wire [29:0] s163;
  reg [1:0] s164;
  reg [59:0] s166 [3:0]; // synthesis attribute ram_style of s166 is block
  reg [59:0] s165;
  reg [25:0] s167;
  reg [25:0] s168;
  wire [29:0] s169;
  wire [25:0] s170;
  reg [29:0] s171;
  reg [29:0] s172;
  reg [29:0] s173;
  wire [29:0] s174;
  wire [29:0] s175;
  wire s176;
  wire s177;
  reg [29:0] s178;
  reg [29:0] s179;
  reg [29:0] s180;
  reg [29:0] s181;
  reg [29:0] s182;
  reg [29:0] s183;
  reg [29:0] s184;
  reg [1:0] s186 [18:0];
  wire [1:0] s185;
  reg [1:0] s188 [11:0];
  wire [1:0] s187;
  wire [29:0] s189;
  wire [29:0] s190;
  wire [29:0] s191;
  wire [29:0] s192;
  wire [55:0] s193;
  wire [29:0] s194;
  reg [1:0] s195;
  reg [29:0] s196;
  reg [29:0] s197;
  reg [25:0] s198;
  wire [29:0] s199;
  wire [29:0] s200;
  reg [29:0] s201;
  wire [29:0] s202;
  wire [29:0] s203;
  wire [29:0] s204;
  wire [29:0] s205;
  wire [59:0] s206;
  reg [29:0] s207;
  reg [29:0] s208;
  reg [29:0] s210 [2:0];
  wire [29:0] s209;
  wire [29:0] s211;
  wire [29:0] s212;
  reg [59:0] s214 [3:0]; // synthesis attribute ram_style of s214 is block
  reg [59:0] s213;
  reg s216;
  reg s215;
  reg [29:0] s217;
  wire [59:0] s218;
  reg [1:0] s220 [11:0];
  wire [1:0] s219;
  reg [25:0] s221;
  wire [29:0] s222;
  wire [29:0] s223;
  wire [29:0] s224;
  wire [29:0] s225;
  reg [25:0] s226;
  wire [29:0] s227;
  wire [29:0] s228;
  wire [25:0] s229;
  reg [1:0] s231 [18:0];
  wire [1:0] s230;
  reg [59:0] s233 [3:0]; // synthesis attribute ram_style of s233 is block
  reg [59:0] s232;
  reg [29:0] s234;
  reg [29:0] s235;
  reg [29:0] s236;
  reg [29:0] s237;
  reg [29:0] s238;
  reg [29:0] s240 [2:0];
  wire [29:0] s239;
  reg [29:0] s242 [2:0];
  wire [29:0] s241;
  wire [29:0] s243;
  reg [29:0] s245 [2:0];
  wire [29:0] s244;
  reg [59:0] s246;
  reg [29:0] s247;
  reg [29:0] s248;
  wire [55:0] s249;
  reg [29:0] s250;
  reg [29:0] s251;
  reg [29:0] s252;
  wire [29:0] s253;
  wire [29:0] s254;
  wire [29:0] s255;
  reg [29:0] s256;
  reg [29:0] s257;
  reg [29:0] s258;
  reg [29:0] s259;
  reg [29:0] s260;
  wire [59:0] s261;
  reg [29:0] s263 [2:0];
  wire [29:0] s262;
  reg [29:0] s264;
  reg [29:0] s265;
  wire [29:0] s266;
  wire [29:0] s267;
  reg s269 [6:0];
  wire s268;
  wire [29:0] s270;
  wire s271;
  wire [29:0] s272;
  reg [29:0] s274 [2:0];
  wire [29:0] s273;
  reg [59:0] s276 [3:0]; // synthesis attribute ram_style of s276 is block
  reg [59:0] s275;
  wire [59:0] s277;
  reg [59:0] s278;
  wire [55:0] s279;
  wire [29:0] s280;
  reg [29:0] s281;
  wire [55:0] s282;
  wire [29:0] s283;
  wire [29:0] s284;
  wire [59:0] s285;
  wire [29:0] s286;
  wire [29:0] s287;
  wire [29:0] s288;
  wire [59:0] s289;
  wire [29:0] s290;
  reg [29:0] s291;
  wire [29:0] s292;
  reg [29:0] s294 [2:0];
  wire [29:0] s293;
  reg [25:0] s295;
  wire [59:0] s296;
  reg [29:0] s298 [2:0];
  wire [29:0] s297;
  wire [29:0] s299;
  reg s300;
  reg [29:0] s301;
  reg [29:0] s302;
  reg [29:0] s303;
  reg [1:0] s304;
  reg [59:0] s306 [3:0]; // synthesis attribute ram_style of s306 is block
  reg [59:0] s305;
  reg [29:0] s307;
  wire [29:0] s308;
  wire [29:0] s309;
  wire [29:0] s310;
  wire [29:0] s311;
  wire [29:0] s312;
  reg [29:0] s313;
  wire [25:0] s314;
  reg [29:0] s315;
  reg [29:0] s316;
  reg [29:0] s317;
  reg [29:0] s318;
  reg [25:0] s319;
  reg s321 [4:0];
  wire s320;
  wire [29:0] s322;
  reg [25:0] s323;
  wire [29:0] s324;
  reg [1:0] s325;
  reg [59:0] s326;
  reg s328 [9:0];
  wire s327;
  reg [59:0] s329;
  wire [29:0] s330;
  wire [29:0] s331;
  wire [29:0] s332;
  wire [29:0] s333;
  reg [59:0] s334;
  reg [1:0] s335;
  wire [29:0] s336;
  wire [29:0] s337;
  wire [29:0] s338;
  wire [29:0] s339;
  reg [29:0] s340;
  reg [29:0] s341;
  reg s343 [8:0];
  wire s342;
  reg [29:0] s344;
  wire [29:0] s345;
  reg [29:0] s346;
  reg [29:0] s347;
  reg [29:0] s348;
  wire [1:0] s349;
  wire [29:0] s350;
  wire [29:0] s351;
  reg [29:0] s352;
  reg [29:0] s353;
  reg [29:0] s354;
  reg [29:0] s355;
  reg [29:0] s356;
  wire [29:0] s357;
  wire [29:0] s358;
  reg s359;
  wire [29:0] s360;
  wire [29:0] s361;
  wire [29:0] s362;
  wire [29:0] s363;
  wire [29:0] s364;
  reg [29:0] s365;
  wire [59:0] s366;
  reg [29:0] s367;
  reg [29:0] s368;
  reg [29:0] s369;
  wire [29:0] s370;
  reg [25:0] s371;
  wire [55:0] s372;
  reg [29:0] s373;
  reg [29:0] s374;
  wire [29:0] s375;
  wire [29:0] s376;
  reg [29:0] s377;
  reg [29:0] s378;
  reg [1:0] s379;
  reg s381 [3:0];
  wire s380;
  reg [1:0] s382;
  reg [25:0] s383;
  reg [1:0] s385 [11:0];
  wire [1:0] s384;
  wire [29:0] s386;
  wire [1:0] s387;
  wire [29:0] s388;
  wire [29:0] s389;
  reg [29:0] s390;
  reg [29:0] s391;
  reg [29:0] s392;
  wire [29:0] s393;
  reg [29:0] s395;
  reg [29:0] s394;
  reg [29:0] s397;
  reg [29:0] s396;
  wire [29:0] s398;
  wire s399;
  wire [29:0] s400;
  wire [29:0] s401;
  wire [29:0] s402;
  wire [29:0] s403;
  wire [29:0] s404;
  reg [29:0] s405;
  wire [29:0] s406;
  reg [29:0] s408 [2:0];
  wire [29:0] s407;
  reg [29:0] s410 [2:0];
  wire [29:0] s409;
  wire [29:0] s411;
  wire [29:0] s412;
  wire [29:0] s413;
  wire [29:0] s414;
  wire [29:0] s415;
  wire [29:0] s416;
  reg [29:0] s417;
  reg [29:0] s418;
  wire [59:0] s419;
  wire [29:0] s420;
  wire [29:0] s421;
  reg [59:0] s423 [3:0]; // synthesis attribute ram_style of s423 is block
  reg [59:0] s422;
  wire [29:0] s424;
  wire [29:0] s425;
  wire [25:0] s426;
  reg [25:0] s427;
  reg [29:0] s428;
  wire [29:0] s429;
  wire [29:0] s430;
  reg [25:0] s431;
  reg [59:0] s433 [3:0]; // synthesis attribute ram_style of s433 is block
  reg [59:0] s432;
  wire [29:0] s434;
  reg [29:0] s435;
  reg [29:0] s436;
  wire [59:0] s437;
  reg [1:0] s438;
  reg [29:0] s440 [3:0];
  wire [29:0] s439;
  reg [59:0] s441;
  reg [1:0] s442;
  reg [29:0] s443;
  wire [25:0] s444;
  wire [29:0] s445;
  wire [29:0] s446;
  reg [29:0] s447;
  reg [25:0] s448;
  reg [29:0] s449;
  reg [29:0] s450;
  reg [29:0] s451;
  reg [25:0] s452;
  wire [59:0] s453;
  wire [1:0] s454;
  reg [29:0] s455;
  reg [29:0] s456;
  wire [29:0] s457;
  wire [29:0] s458;
  reg [29:0] s459;
  reg s461 [3:0];
  wire s460;
  wire [29:0] s462;
  reg [29:0] s463;
  reg [29:0] s464;
  wire [29:0] s465;
  wire [29:0] s466;
  reg [29:0] s467;
  reg [1:0] s468;
  reg [29:0] s469;
  reg [29:0] s470;
  wire [59:0] s471;
  wire [29:0] s472;
  wire [29:0] s473;
  reg [1:0] s475 [18:0];
  wire [1:0] s474;
  reg [25:0] s476;
  wire [29:0] s477;
  wire [29:0] s478;
  reg [29:0] s479;
  reg [29:0] s480;
  reg [29:0] s481;
  wire [29:0] s482;
  wire [29:0] s483;
  reg [29:0] s484;
  reg [29:0] s485;
  reg [59:0] s486;
  reg [29:0] s487;
  wire [29:0] s488;
  wire [29:0] s489;
  reg [29:0] s490;
  reg [29:0] s491;
  reg [29:0] s493;
  reg [29:0] s492;
  reg [29:0] s495;
  reg [29:0] s494;
  reg [29:0] s496;
  reg [29:0] s497;
  wire [1:0] s498;
  wire [29:0] s499;
  wire [29:0] s500;
  reg [59:0] s502 [3:0]; // synthesis attribute ram_style of s502 is block
  reg [59:0] s501;
  wire [1:0] s503;
  wire [1:0] s504;
  wire [29:0] s505;
  wire [29:0] s506;
  reg [59:0] s508 [3:0]; // synthesis attribute ram_style of s508 is block
  reg [59:0] s507;
  reg [29:0] s509;
  wire [29:0] s510;
  wire [29:0] s511;
  wire [29:0] s512;
  wire [29:0] s513;
  wire [29:0] s514;
  wire [29:0] s515;
  reg [29:0] s517;
  reg [29:0] s516;
  reg [29:0] s519;
  reg [29:0] s518;
  reg [29:0] s520;
  reg [1:0] s521;
  reg [25:0] s522;
  wire [29:0] s523;
  wire [29:0] s524;
  wire [59:0] s525;
  wire [29:0] s526;
  reg [29:0] s527;
  reg [59:0] s528;
  wire [29:0] s529;
  wire [29:0] s530;
  wire [29:0] s531;
  reg [29:0] s533;
  reg [29:0] s532;
  reg [29:0] s535;
  reg [29:0] s534;
  wire [29:0] s536;
  reg [59:0] s537;
  wire [29:0] s538;
  wire [29:0] s539;
  wire [29:0] s540;
  wire [29:0] s541;
  reg [59:0] s542;
  wire [29:0] s543;
  wire [29:0] s544;
  reg [29:0] s545;
  reg [29:0] s546;
  reg [29:0] s547;
  wire s548;
  integer i;
  assign s2 = s481 - s470;
  assign s3 = s300 ? s251 : s391;
  assign s4 = s300 ? s252 : s392;
  assign s5 = {s238, s315};
  assign s6 = s172 + s490;
  assign s7 = s173 + s491;
  assign s14 = s325[0];
  assign s15 = s215 ? s532 : s10;
  assign s16 = s215 ? s534 : s12;
  assign s20 = {s41, s42};
  assign s21 = s94 ? s527 : s346;
  assign s22 = s460 ? s82 : s207;
  assign s23 = s24 [3];
  assign s26 = {s313, s547};
  assign s27 = s94 ? s123 : s162;
  assign s28 = s215 ? s516 : s394;
  assign s29 = s215 ? s518 : s396;
  assign o0 = s525;
  assign s31 = s32 [18];
  assign s33 = s34 [4];
  assign s35 = s36 [4];
  assign s37 = s373 + s142;
  assign s38 = s374 + s143;
  assign s39 = s177 ? s152 : s429;
  assign s40 = s177 ? s153 : s430;
  assign s45 = s46 [8];
  assign s47 = s334[59:30];
  assign s48 = s334[29:0];
  assign s50 = s160 ? s146 : s353;
  assign s51 = s160 ? s147 : s354;
  assign s52 = s372[53:24];
  assign s53 = s54 [37];
  assign s57 = s431 - s167;
  assign s58 = s59 [22];
  assign s60 = s327 ? s235 : s25;
  assign s63 = s64 [2];
  assign s73 = s87 ? s477 : s253;
  assign s74 = s87 ? s478 : s254;
  assign s77 = s134[53:24];
  assign s80 = s81 [3];
  assign s84 = s80 ? s131 : s257;
  assign s85 = s160 ? s353 : s146;
  assign s86 = s160 ? s354 : s147;
  assign s87 = s88 [6];
  assign s89 = $signed(s487) * $signed(s198);
  assign s90 = s380 ? s504 : s325;
  assign s91 = s92 [6];
  assign s93 = s80 ? s257 : s131;
  assign s94 = s95 [2];
  assign s96 = s262 + s273;
  assign s97 = s80 ? s217 : s178;
  assign s98 = s160 ? s484 : s148;
  assign s99 = s160 ? s485 : s149;
  assign s101 = s438 ^ s195;
  assign s102 = s164 ^ s195;
  assign s103 = s377 - s378;
  assign s109 = s67 ? s492 : s124;
  assign s110 = s67 ? s494 : s126;
  assign s114 = s140 + s49;
  assign s115 = s87 ? s199 : s222;
  assign s116 = s87 ? s200 : s223;
  assign s119 = s120 [4];
  assign s121 = s122 [4];
  assign s128 = $signed(s260) * $signed(s168);
  assign s129 = s94 ? s113 : s443;
  assign s130 = {s177, s176};
  assign s132 = s327 ? s25 : s235;
  assign s133 = s281 + s447;
  assign s134 = $signed(s157) * $signed(26'd11863283);
  assign s136 = s359 ? s130 : s468;
  assign s137 = s75 - s365;
  assign s138 = s139 [11];
  assign s150 = s94 ? s346 : s527;
  assign s151 = $signed(s407) * $signed(s221);
  assign s152 = i0[59:30];
  assign s153 = i0[29:0];
  assign s154 = s460 ? s497 : s83;
  assign s158 = s80 ? s390 : s450;
  assign s159 = s327 ? s355 : s367;
  assign s160 = s161 [8];
  assign s163 = s418 + s449;
  assign o1 = s218;
  assign s169 = s463 - s256;
  assign s170 = s522 - s383;
  assign s174 = s268 ? s403 : s523;
  assign s175 = s268 ? s404 : s524;
  assign s176 = s468[1];
  assign s177 = s468[0];
  assign s185 = s186 [18];
  assign s187 = s188 [11];
  assign s189 = s268 ? s523 : s403;
  assign s190 = s268 ? s524 : s404;
  assign s191 = s246[59:30];
  assign s192 = s246[29:0];
  assign s193 = $signed(s9) * $signed(s371);
  assign s194 = s460 ? s496 : s181;
  assign s199 = s326[59:30];
  assign s200 = s326[29:0];
  assign s202 = s177 ? s375 : s499;
  assign s203 = s177 ? s376 : s500;
  assign s204 = s89[53:24];
  assign o3 = s296;
  assign s205 = s327 ? s464 : s417;
  assign s206 = {s509, s356};
  assign s209 = s210 [2];
  assign s211 = s460 ? s181 : s496;
  assign s212 = s279[53:24];
  assign s218 = {s61, s62};
  assign s219 = s220 [11];
  assign s222 = s329[59:30];
  assign s223 = s329[29:0];
  assign s224 = s45 ? s264 : s17;
  assign s225 = s45 ? s265 : s18;
  assign s227 = s177 ? s499 : s375;
  assign s228 = s177 ? s500 : s376;
  assign s229 = s135 + s43;
  assign s230 = s231 [18];
  assign s239 = s240 [2];
  assign s241 = s242 [2];
  assign s243 = s545 - s546;
  assign s244 = s245 [2];
  assign s249 = $signed(s409) * $signed(s427);
  assign s253 = s486[59:30];
  assign s254 = s486[29:0];
  assign s255 = s75 + s365;
  assign s261 = {s155, s156};
  assign s262 = s263 [2];
  assign s266 = s87 ? s222 : s199;
  assign s267 = s87 ? s223 : s200;
  assign s268 = s269 [6];
  assign s270 = s193[53:24];
  assign s271 = s380 ? s399 : s359;
  assign s272 = s94 ? s258 : s467;
  assign s273 = s274 [2];
  assign s277 = {s307, s520};
  assign s279 = $signed(s56) * $signed(s226);
  assign s280 = s141 - s1;
  assign s282 = $signed(s55) * $signed(s452);
  assign s283 = s418 - s449;
  assign s284 = s463 + s256;
  assign s285 = {s179, s180};
  assign s286 = s19 + s259;
  assign s287 = s479 + s183;
  assign s288 = s480 + s184;
  assign s289 = {s250, s459};
  assign s290 = s297 + s63;
  assign s292 = s244 - s63;
  assign s293 = s294 [2];
  assign s296 = {s105, s106};
  assign s297 = s298 [2];
  assign s299 = s293 - s344;
  assign s308 = s460 ? s182 : s208;
  assign s309 = s278[59:30];
  assign s310 = s278[29:0];
  assign s311 = s373 - s142;
  assign s312 = s374 - s143;
  assign s314 = s431 + s167;
  assign s320 = s321 [4];
  assign s322 = s460 ? s83 : s497;
  assign s324 = s327 ? s417 : s464;
  assign s327 = s328 [9];
  assign s330 = s300 ? s247 : s435;
  assign s331 = s300 ? s248 : s436;
  assign s332 = s140 - s49;
  assign s333 = s209 + s344;
  assign s336 = s80 ? s178 : s217;
  assign s337 = s141 + s1;
  assign s338 = s67 ? s303 : s369;
  assign s339 = s67 ? s302 : s368;
  assign s342 = s343 [8];
  assign o2 = s437;
  assign s345 = s94 ? s467 : s258;
  assign s349 = s14 ? 2'd3 : 2'd0;
  assign s350 = s479 - s183;
  assign s351 = s480 - s184;
  assign s357 = s107[59:30];
  assign s358 = s107[29:0];
  assign s360 = 30'd0 - s241;
  assign s361 = s45 ? s78 : s317;
  assign s362 = s45 ? s79 : s318;
  assign s363 = s177 ? s429 : s152;
  assign s364 = s177 ? s430 : s153;
  assign s366 = {s201, s171};
  assign s370 = s151[53:24];
  assign s372 = $signed(s8) * $signed(s319);
  assign s375 = i1[59:30];
  assign s376 = i1[29:0];
  assign s380 = s381 [3];
  assign s384 = s385 [11];
  assign s386 = s80 ? s451 : s469;
  assign s387 = next ? 2'd0 : s503;
  assign s388 = s268 ? s309 : s465;
  assign s389 = s268 ? s310 : s466;
  assign s393 = s94 ? s443 : s113;
  assign s398 = s481 + s470;
  assign s399 = s359 + 1'd1;
  assign s400 = s320 ? s33 : s119;
  assign s401 = s320 ? s35 : s121;
  assign s402 = s239 - s273;
  assign s403 = s441[59:30];
  assign s404 = s441[29:0];
  assign s406 = s460 ? s208 : s182;
  assign s407 = s408 [2];
  assign s409 = s410 [2];
  assign s411 = s19 - s259;
  assign s412 = s340 - s341;
  assign s413 = s45 ? s17 : s264;
  assign s414 = s45 ? s18 : s265;
  assign s415 = s160 ? s148 : s484;
  assign s416 = s160 ? s149 : s485;
  assign s419 = {s352, s234};
  assign s420 = s91 ? s192 : s358;
  assign s421 = s91 ? s191 : s357;
  assign s424 = s268 ? s465 : s309;
  assign s425 = s268 ? s466 : s310;
  assign s426 = s135 - s43;
  assign s429 = i2[59:30];
  assign s430 = i2[29:0];
  assign s434 = s327 ? s30 : s291;
  assign s437 = {s455, s456};
  assign s439 = s440 [3];
  assign s444 = s522 + s383;
  assign s445 = s300 ? s391 : s251;
  assign s446 = s300 ? s392 : s252;
  assign s453 = {s69, s70};
  assign next_out = s53;
  assign s454 = s379 ^ s195;
  assign s457 = s87 ? s253 : s477;
  assign s458 = s87 ? s254 : s478;
  assign s460 = s461 [3];
  assign s462 = s460 ? s207 : s82;
  assign s465 = s537[59:30];
  assign s466 = s537[29:0];
  assign s471 = {s301, s44};
  assign s472 = s342 ? s71 : s196;
  assign s473 = s342 ? s72 : s197;
  assign s474 = s475 [18];
  assign s477 = s542[59:30];
  assign s478 = s542[29:0];
  assign s482 = s348 - s66;
  assign s483 = s347 - s65;
  assign s488 = s282[53:24];
  assign s489 = s80 ? s469 : s451;
  assign s498 = reset ? 2'd0 : s90;
  assign s499 = i3[59:30];
  assign s500 = i3[29:0];
  assign s503 = s468 + 2'd1;
  assign s504 = s325 + 2'd1;
  assign s505 = s45 ? s317 : s78;
  assign s506 = s45 ? s318 : s79;
  assign s510 = s528[59:30];
  assign s511 = s528[29:0];
  assign s512 = s94 ? s162 : s123;
  assign s513 = s327 ? s367 : s355;
  assign s514 = s347 + s65;
  assign s515 = s348 + s66;
  assign s523 = s100[59:30];
  assign s524 = s100[29:0];
  assign s525 = {s236, s237};
  assign s526 = s281 - s447;
  assign s529 = s300 ? s435 : s247;
  assign s530 = s300 ? s436 : s248;
  assign s531 = s327 ? s291 : s30;
  assign s536 = s80 ? s450 : s390;
  assign s538 = s172 - s490;
  assign s539 = s173 - s491;
  assign s540 = s128[53:24];
  assign s541 = s249[53:24];
  assign s543 = s91 ? s48 : s511;
  assign s544 = s91 ? s47 : s510;
  assign s548 = reset ? 1'd0 : s271;
  always @(*)
    case(s325)
      0: s76 = 2'd0;
      1: s76 = 2'd1;
      2: s76 = 2'd3;
      3: s76 = 2'd2;
    endcase
  always @(*)
    case(s58)
      0: s104 = 26'd0;
      1: s104 = 26'd11863283;
      2: s104 = 26'd16777216;
      3: s104 = 26'd11863283;
    endcase
  always @(*)
    case(s58)
      0: s108 = 26'd0;
      1: s108 = 26'd51608738;
      2: s108 = 26'd55245581;
      3: s108 = 26'd6420362;
    endcase
  always @(*)
    case(s58)
      0: s295 = 26'd16777216;
      1: s295 = 26'd15500126;
      2: s295 = 26'd11863283;
      3: s295 = 26'd6420362;
    endcase
  always @(*)
    case(s325)
      0: s304 = 2'd0;
      1: s304 = 2'd2;
      2: s304 = 2'd3;
      3: s304 = 2'd1;
    endcase
  always @(*)
    case(s58)
      0: s323 = 26'd16777216;
      1: s323 = 26'd6420362;
      2: s323 = 26'd55245581;
      3: s323 = 26'd51608738;
    endcase
  always @(*)
    case(s23)
      0: s405 = s439;
      1: s405 = s428;
      2: s405 = 30'd0;
      3: s405 = s316;
    endcase
  always @(*)
    case(s58)
      0: s448 = 26'd0;
      1: s448 = 26'd6420362;
      2: s448 = 26'd11863283;
      3: s448 = 26'd15500126;
    endcase
  always @(*)
    case(s58)
      0: s476 = 26'd16777216;
      1: s476 = 26'd11863283;
      2: s476 = 26'd0;
      3: s476 = 26'd55245581;
    endcase
  always @(posedge clk)
    begin
      s1 <= s333;
      s8 <= s545;
      s9 <= s546;
      s11 <= s191;
      s10 <= s11;
      s13 <= s192;
      s12 <= s13;
      s17 <= s457;
      s18 <= s458;
      s19 <= s290;
      s24 [0] <= s58;
      for (i = 1; i < 4; i = i + 1)
        s24 [i] <= s24 [i - 1];
      s25 <= s393;
      s30 <= s345;
      s32 [0] <= s219;
      for (i = 1; i < 19; i = i + 1)
        s32 [i] <= s32 [i - 1];
      s34 [0] <= s368;
      for (i = 1; i < 5; i = i + 1)
        s34 [i] <= s34 [i - 1];
      s36 [0] <= s369;
      for (i = 1; i < 5; i = i + 1)
        s36 [i] <= s36 [i - 1];
      s41 <= s529;
      s42 <= s530;
      s43 <= s108;
      s44 <= s159;
      s46 [0] <= s177;
      for (i = 1; i < 9; i = i + 1)
        s46 [i] <= s46 [i - 1];
      s49 <= s299;
      s54 [0] <= s380;
      for (i = 1; i < 38; i = i + 1)
        s54 [i] <= s54 [i - 1];
      s55 <= s340;
      s56 <= s341;
      s59 [0] <= s468;
      for (i = 1; i < 23; i = i + 1)
        s59 [i] <= s59 [i - 1];
      s61 <= s98;
      s62 <= s99;
      s64 [0] <= s204;
      for (i = 1; i < 3; i = i + 1)
        s64 [i] <= s64 [i - 1];
      s65 <= s361;
      s66 <= s362;
      s68 <= s342;
      s67 <= s68;
      s69 <= s3;
      s70 <= s4;
      s71 <= s420;
      s72 <= s421;
      s75 <= s114;
      s78 <= s266;
      s79 <= s267;
      s81 [0] <= s45;
      for (i = 1; i < 4; i = i + 1)
        s81 [i] <= s81 [i - 1];
      s82 <= s311;
      s83 <= s312;
      s88 [0] <= s300;
      for (i = 1; i < 7; i = i + 1)
        s88 [i] <= s88 [i - 1];
      s92 [0] <= s80;
      for (i = 1; i < 7; i = i + 1)
        s92 [i] <= s92 [i - 1];
      s95 [0] <= s320;
      for (i = 1; i < 3; i = i + 1)
        s95 [i] <= s95 [i - 1];
      s100 <= s422;
      s105 <= s50;
      s106 <= s51;
      s107 <= s305;
      s112 [s31] <= s26;
      s111 <= s112 [s31];
      s113 <= s137;
      s118 [s474] <= s471;
      s117 <= s118 [s474];
      s120 [0] <= s302;
      for (i = 1; i < 5; i = i + 1)
        s120 [i] <= s120 [i - 1];
      s122 [0] <= s303;
      for (i = 1; i < 5; i = i + 1)
        s122 [i] <= s122 [i - 1];
      s123 <= s133;
      s125 <= s71;
      s124 <= s125;
      s127 <= s72;
      s126 <= s127;
      s131 <= s22;
      s135 <= s323;
      s139 [0] <= s442;
      for (i = 1; i < 12; i = i + 1)
        s139 [i] <= s139 [i - 1];
      s140 <= s400;
      s141 <= s401;
      s142 <= s482;
      s143 <= s483;
      s145 [s335] <= s285;
      s144 <= s145 [s335];
      s146 <= s174;
      s147 <= s175;
      s148 <= s388;
      s149 <= s389;
      s155 <= s445;
      s156 <= s446;
      s157 <= s103;
      s161 [0] <= s94;
      for (i = 1; i < 9; i = i + 1)
        s161 [i] <= s161 [i - 1];
      s162 <= s284;
      s164 <= s349;
      s166 [s138] <= s289;
      s165 <= s166 [s138];
      s167 <= s448;
      s168 <= s135;
      s171 <= s536;
      s172 <= s413;
      s173 <= s414;
      s178 <= s154;
      s179 <= s330;
      s180 <= s331;
      s181 <= s37;
      s182 <= s38;
      s183 <= s514;
      s184 <= s515;
      s186 [0] <= s138;
      for (i = 1; i < 19; i = i + 1)
        s186 [i] <= s186 [i - 1];
      s188 [0] <= s382;
      for (i = 1; i < 12; i = i + 1)
        s188 [i] <= s188 [i - 1];
      s195 <= s136;
      s196 <= s543;
      s197 <= s544;
      s198 <= s431;
      s201 <= s84;
      s207 <= s287;
      s208 <= s288;
      s210 [0] <= s541;
      for (i = 1; i < 3; i = i + 1)
        s210 [i] <= s210 [i - 1];
      s214 [s521] <= s453;
      s213 <= s214 [s521];
      s216 <= s91;
      s215 <= s216;
      s217 <= s406;
      s220 [0] <= s335;
      for (i = 1; i < 12; i = i + 1)
        s220 [i] <= s220 [i - 1];
      s221 <= s170;
      s226 <= s426;
      s231 [0] <= s187;
      for (i = 1; i < 19; i = i + 1)
        s231 [i] <= s231 [i - 1];
      s233 [s230] <= s5;
      s232 <= s233 [s230];
      s234 <= s97;
      s235 <= s21;
      s236 <= s415;
      s237 <= s416;
      s238 <= s60;
      s240 [0] <= s488;
      for (i = 1; i < 3; i = i + 1)
        s240 [i] <= s240 [i - 1];
      s242 [0] <= s77;
      for (i = 1; i < 3; i = i + 1)
        s242 [i] <= s242 [i - 1];
      s245 [0] <= s52;
      for (i = 1; i < 3; i = i + 1)
        s245 [i] <= s245 [i - 1];
      s246 <= s432;
      s247 <= s39;
      s248 <= s40;
      s250 <= s489;
      s251 <= s227;
      s252 <= s228;
      s256 <= s2;
      s257 <= s211;
      s258 <= s169;
      s259 <= s96;
      s260 <= s412;
      s263 [0] <= s212;
      for (i = 1; i < 3; i = i + 1)
        s263 [i] <= s263 [i - 1];
      s264 <= s115;
      s265 <= s116;
      s269 [0] <= s327;
      for (i = 1; i < 7; i = i + 1)
        s269 [i] <= s269 [i - 1];
      s274 [0] <= s540;
      for (i = 1; i < 3; i = i + 1)
        s274 [i] <= s274 [i - 1];
      s276 [s442] <= s261;
      s275 <= s276 [s442];
      s278 <= s111;
      s281 <= s337;
      s291 <= s27;
      s294 [0] <= s370;
      for (i = 1; i < 3; i = i + 1)
        s294 [i] <= s294 [i - 1];
      s298 [0] <= s270;
      for (i = 1; i < 3; i = i + 1)
        s298 [i] <= s298 [i - 1];
      s300 <= s176;
      s301 <= s132;
      s302 <= s28;
      s303 <= s29;
      s306 [s219] <= s419;
      s305 <= s306 [s219];
      s307 <= s324;
      s313 <= s205;
      s315 <= s513;
      s316 <= s360;
      s317 <= s73;
      s318 <= s74;
      s319 <= s57;
      s321 [0] <= s67;
      for (i = 1; i < 5; i = i + 1)
        s321 [i] <= s321 [i - 1];
      s325 <= s498;
      s326 <= s501;
      s328 [0] <= s215;
      for (i = 1; i < 10; i = i + 1)
        s328 [i] <= s328 [i - 1];
      s329 <= s213;
      s334 <= s507;
      s335 <= s102;
      s340 <= s109;
      s341 <= s110;
      s343 [0] <= s460;
      for (i = 1; i < 9; i = i + 1)
        s343 [i] <= s343 [i - 1];
      s344 <= s405;
      s346 <= s255;
      s347 <= s505;
      s348 <= s506;
      s352 <= s386;
      s353 <= s424;
      s354 <= s425;
      s355 <= s272;
      s356 <= s158;
      s359 <= s548;
      s365 <= s398;
      s367 <= s512;
      s368 <= s15;
      s369 <= s16;
      s371 <= s314;
      s373 <= s538;
      s374 <= s539;
      s377 <= s472;
      s378 <= s473;
      s379 <= s76;
      s381 [0] <= next;
      for (i = 1; i < 4; i = i + 1)
        s381 [i] <= s381 [i - 1];
      s382 <= s454;
      s383 <= s104;
      s385 [0] <= s521;
      for (i = 1; i < 12; i = i + 1)
        s385 [i] <= s385 [i - 1];
      s390 <= s322;
      s391 <= s363;
      s392 <= s364;
      s395 <= s47;
      s394 <= s395;
      s397 <= s48;
      s396 <= s397;
      s408 [0] <= s377;
      for (i = 1; i < 3; i = i + 1)
        s408 [i] <= s408 [i - 1];
      s410 [0] <= s378;
      for (i = 1; i < 3; i = i + 1)
        s410 [i] <= s410 [i - 1];
      s417 <= s129;
      s418 <= s332;
      s423 [s185] <= s277;
      s422 <= s423 [s185];
      s427 <= s444;
      s428 <= s241;
      s431 <= s295;
      s433 [s187] <= s366;
      s432 <= s433 [s187];
      s435 <= s202;
      s436 <= s203;
      s438 <= s304;
      s440 [0] <= s157;
      for (i = 1; i < 4; i = i + 1)
        s440 [i] <= s440 [i - 1];
      s441 <= s117;
      s442 <= s101;
      s443 <= s163;
      s447 <= s286;
      s449 <= s411;
      s450 <= s308;
      s451 <= s462;
      s452 <= s229;
      s455 <= s85;
      s456 <= s86;
      s459 <= s336;
      s461 [0] <= s87;
      for (i = 1; i < 4; i = i + 1)
        s461 [i] <= s461 [i - 1];
      s463 <= s280;
      s464 <= s150;
      s467 <= s526;
      s468 <= s387;
      s469 <= s194;
      s470 <= s402;
      s475 [0] <= s384;
      for (i = 1; i < 19; i = i + 1)
        s475 [i] <= s475 [i - 1];
      s479 <= s6;
      s480 <= s7;
      s481 <= s292;
      s484 <= s189;
      s485 <= s190;
      s486 <= s144;
      s487 <= s243;
      s490 <= s224;
      s491 <= s225;
      s493 <= s196;
      s492 <= s493;
      s495 <= s197;
      s494 <= s495;
      s496 <= s350;
      s497 <= s351;
      s502 [s382] <= s20;
      s501 <= s502 [s382];
      s508 [s384] <= s206;
      s507 <= s508 [s384];
      s509 <= s93;
      s517 <= s510;
      s516 <= s517;
      s519 <= s511;
      s518 <= s519;
      s520 <= s434;
      s521 <= s195;
      s522 <= s476;
      s527 <= s283;
      s528 <= s165;
      s533 <= s357;
      s532 <= s533;
      s535 <= s358;
      s534 <= s535;
      s537 <= s232;
      s542 <= s275;
      s545 <= s338;
      s546 <= s339;
      s547 <= s531;
    end
endmodule
