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
 * - i0 - i3: The components of the chunks of the dataset. Each of these elements is a complex number in cartesian form (real and imaginary part are concatenated, each being a signed fixed-point number (18. 20 bits representation)).
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

module FptSGenForwardGuarded16x4(input clk,
  input reset,
  input next,
  input [75:0] i0,
  input [75:0] i1,
  input [75:0] i2,
  input [75:0] i3,
  output next_out,
  output [75:0] o0,
  output [75:0] o1,
  output [75:0] o2,
  output [75:0] o3);

  wire [37:0] s1;
  wire [37:0] s2;
  wire [33:0] s3;
  wire [37:0] s4;
  wire [37:0] s5;
  wire [37:0] s6;
  reg [37:0] s7;
  reg [37:0] s8;
  reg [37:0] s10;
  reg [37:0] s9;
  reg [37:0] s12;
  reg [37:0] s11;
  reg [37:0] s13;
  reg [37:0] s14;
  wire s15;
  reg [75:0] s16;
  reg [37:0] s17;
  wire [37:0] s18;
  wire [37:0] s19;
  reg [37:0] s20;
  reg [37:0] s21;
  wire [75:0] s22;
  reg [37:0] s23;
  wire [37:0] s24;
  reg [1:0] s26 [3:0];
  wire [1:0] s25;
  wire [37:0] s27;
  wire [37:0] s28;
  wire [37:0] s29;
  reg [1:0] s31 [18:0];
  wire [1:0] s30;
  reg [37:0] s32;
  reg [37:0] s34 [4:0];
  wire [37:0] s33;
  reg [37:0] s36 [4:0];
  wire [37:0] s35;
  wire [37:0] s37;
  wire [37:0] s38;
  wire [37:0] s39;
  wire [37:0] s40;
  reg [37:0] s41;
  reg [37:0] s42;
  reg [37:0] s43;
  wire [33:0] s44;
  wire [75:0] s45;
  reg [37:0] s46;
  reg s48 [8:0];
  wire s47;
  wire [37:0] s49;
  wire [37:0] s50;
  wire [37:0] s51;
  wire [37:0] s52;
  reg s54 [37:0];
  wire s53;
  wire [37:0] s55;
  reg [37:0] s56;
  reg [37:0] s57;
  reg [1:0] s59 [22:0];
  wire [1:0] s58;
  reg [37:0] s61 [2:0];
  wire [37:0] s60;
  reg [37:0] s63 [2:0];
  wire [37:0] s62;
  reg [37:0] s64;
  reg [37:0] s65;
  reg s67;
  reg s66;
  reg [37:0] s68;
  reg [37:0] s69;
  wire [37:0] s70;
  wire [37:0] s71;
  reg [37:0] s72;
  reg [37:0] s73;
  wire [37:0] s74;
  wire [37:0] s75;
  wire [37:0] s76;
  reg [1:0] s77;
  wire [33:0] s78;
  wire [37:0] s79;
  reg [37:0] s80;
  reg [37:0] s81;
  reg [37:0] s82;
  reg s84 [3:0];
  wire s83;
  wire [75:0] s85;
  reg [37:0] s86;
  reg [37:0] s87;
  wire [37:0] s88;
  wire [37:0] s89;
  wire [37:0] s90;
  reg s92 [6:0];
  wire s91;
  wire [1:0] s93;
  reg [33:0] s94;
  reg s96 [6:0];
  wire s95;
  wire [37:0] s97;
  reg [37:0] s98;
  reg [37:0] s99;
  reg [37:0] s101 [2:0];
  wire [37:0] s100;
  reg s103 [2:0];
  wire s102;
  wire [37:0] s104;
  wire [71:0] s105;
  reg [37:0] s106;
  wire [37:0] s107;
  wire [1:0] s108;
  reg [37:0] s109;
  reg [37:0] s110;
  wire [1:0] s111;
  reg [37:0] s112;
  reg [37:0] s113;
  wire [37:0] s114;
  reg [33:0] s115;
  reg [37:0] s117 [2:0];
  wire [37:0] s116;
  reg [75:0] s118;
  wire [37:0] s119;
  wire [37:0] s120;
  wire [37:0] s121;
  reg [75:0] s122;
  reg [37:0] s123;
  wire [37:0] s124;
  wire [37:0] s125;
  reg [37:0] s127 [2:0];
  wire [37:0] s126;
  reg [37:0] s129 [4:0];
  wire [37:0] s128;
  reg [37:0] s131 [4:0];
  wire [37:0] s130;
  reg [37:0] s133;
  reg [37:0] s132;
  reg [37:0] s135;
  reg [37:0] s134;
  wire [37:0] s136;
  wire [37:0] s137;
  wire [1:0] s138;
  reg [37:0] s139;
  wire [1:0] s140;
  reg [1:0] s142 [11:0];
  wire [1:0] s141;
  reg [37:0] s143;
  reg [37:0] s144;
  reg [37:0] s146 [2:0];
  wire [37:0] s145;
  reg [37:0] s147;
  reg [37:0] s148;
  reg [75:0] s150 [3:0]; // synthesis attribute ram_style of s150 is block
  reg [75:0] s149;
  wire [37:0] s151;
  wire [71:0] s152;
  reg [37:0] s153;
  wire [37:0] s154;
  wire [37:0] s155;
  reg [37:0] s156;
  wire [37:0] s157;
  reg [37:0] s158;
  reg [37:0] s159;
  wire [37:0] s160;
  reg [37:0] s161;
  wire [37:0] s162;
  wire [71:0] s163;
  wire [37:0] s164;
  wire [37:0] s165;
  reg [33:0] s166;
  reg s168 [8:0];
  wire s167;
  reg [37:0] s169;
  reg [1:0] s170;
  wire [37:0] s171;
  reg [75:0] s173 [3:0]; // synthesis attribute ram_style of s173 is block
  reg [75:0] s172;
  reg [37:0] s174;
  reg [37:0] s175;
  reg [37:0] s176;
  wire s177;
  wire s178;
  wire [37:0] s179;
  reg [37:0] s180;
  wire [37:0] s181;
  reg [37:0] s182;
  reg [37:0] s183;
  wire [37:0] s184;
  reg [37:0] s185;
  reg [37:0] s186;
  reg [37:0] s187;
  reg [37:0] s188;
  reg [37:0] s189;
  wire [75:0] s190;
  reg [1:0] s192 [18:0];
  wire [1:0] s191;
  wire [37:0] s193;
  wire [37:0] s194;
  wire [37:0] s195;
  wire [37:0] s196;
  reg [1:0] s198 [11:0];
  wire [1:0] s197;
  wire [37:0] s199;
  wire [37:0] s200;
  reg [37:0] s201;
  wire [37:0] s202;
  reg [1:0] s203;
  reg [37:0] s204;
  reg [37:0] s205;
  reg [37:0] s206;
  reg [37:0] s207;
  wire [37:0] s208;
  wire [37:0] s209;
  reg [37:0] s210;
  wire [37:0] s211;
  wire [37:0] s212;
  wire [37:0] s213;
  wire [37:0] s214;
  reg [37:0] s215;
  wire [37:0] s216;
  wire [37:0] s217;
  wire [37:0] s218;
  wire [75:0] s219;
  reg [37:0] s220;
  reg [37:0] s221;
  reg [37:0] s222;
  wire [37:0] s223;
  wire [37:0] s224;
  reg [37:0] s225;
  reg [75:0] s227 [3:0]; // synthesis attribute ram_style of s227 is block
  reg [75:0] s226;
  reg s229;
  reg s228;
  reg [37:0] s230;
  reg [75:0] s232 [3:0]; // synthesis attribute ram_style of s232 is block
  reg [75:0] s231;
  reg [75:0] s233;
  reg [1:0] s235 [11:0];
  wire [1:0] s234;
  wire [37:0] s236;
  wire [37:0] s237;
  wire [71:0] s238;
  wire [37:0] s239;
  wire [37:0] s240;
  wire [37:0] s241;
  wire [37:0] s242;
  reg [1:0] s244 [18:0];
  wire [1:0] s243;
  reg [37:0] s245;
  reg [37:0] s246;
  wire [37:0] s247;
  wire [37:0] s248;
  wire [37:0] s249;
  reg [75:0] s250;
  reg [37:0] s251;
  reg [37:0] s252;
  reg [37:0] s253;
  reg [37:0] s254;
  reg [37:0] s255;
  wire [37:0] s256;
  wire [37:0] s257;
  reg [37:0] s258;
  reg [37:0] s259;
  reg [37:0] s260;
  reg [37:0] s261;
  reg [37:0] s262;
  reg [37:0] s263;
  wire [75:0] s264;
  reg [75:0] s266 [3:0]; // synthesis attribute ram_style of s266 is block
  reg [75:0] s265;
  reg [37:0] s267;
  reg [37:0] s268;
  wire [75:0] s269;
  wire [37:0] s270;
  wire [37:0] s271;
  reg s273 [6:0];
  wire s272;
  reg [37:0] s274;
  wire s275;
  reg [33:0] s276;
  reg [75:0] s278 [3:0]; // synthesis attribute ram_style of s278 is block
  reg [75:0] s277;
  wire [33:0] s279;
  wire [71:0] s280;
  reg [37:0] s281;
  wire [71:0] s282;
  wire [33:0] s283;
  wire [75:0] s284;
  wire [37:0] s285;
  wire [37:0] s286;
  wire [75:0] s287;
  wire [37:0] s288;
  reg [33:0] s289;
  reg s290;
  reg [37:0] s291;
  reg [37:0] s292;
  wire [33:0] s293;
  wire [37:0] s294;
  wire [37:0] s295;
  reg [1:0] s296;
  reg [37:0] s297;
  reg [75:0] s299 [3:0]; // synthesis attribute ram_style of s299 is block
  reg [75:0] s298;
  wire [37:0] s300;
  wire [37:0] s301;
  wire [37:0] s302;
  wire [37:0] s303;
  reg [33:0] s304;
  wire [37:0] s305;
  reg [33:0] s306;
  reg [37:0] s307;
  reg [37:0] s308;
  reg [37:0] s309;
  reg [37:0] s310;
  reg s312 [4:0];
  wire s311;
  wire [37:0] s313;
  wire [37:0] s314;
  reg [33:0] s315;
  wire [37:0] s316;
  wire [75:0] s317;
  reg [1:0] s318;
  reg [37:0] s319;
  reg [75:0] s320;
  reg s322 [9:0];
  wire s321;
  reg [75:0] s323;
  wire [37:0] s324;
  wire [37:0] s325;
  wire [37:0] s326;
  wire [37:0] s327;
  reg [37:0] s329 [2:0];
  wire [37:0] s328;
  wire [37:0] s330;
  reg [75:0] s331;
  reg [1:0] s332;
  wire [37:0] s333;
  wire [37:0] s334;
  wire [37:0] s335;
  reg [37:0] s336;
  reg [37:0] s337;
  reg s339 [8:0];
  wire s338;
  wire [75:0] s340;
  reg [75:0] s341;
  wire [37:0] s342;
  reg [37:0] s343;
  reg [37:0] s344;
  reg [37:0] s345;
  wire [1:0] s346;
  wire [37:0] s347;
  wire [37:0] s348;
  reg [37:0] s349;
  wire [37:0] s350;
  wire [37:0] s351;
  wire [37:0] s352;
  wire [37:0] s353;
  wire [37:0] s354;
  reg [33:0] s355;
  reg [37:0] s356;
  wire [37:0] s357;
  wire [37:0] s358;
  wire [37:0] s359;
  reg [37:0] s360;
  reg s361;
  wire [71:0] s362;
  wire [37:0] s363;
  wire [37:0] s364;
  wire [37:0] s365;
  wire [37:0] s366;
  wire [37:0] s367;
  wire [37:0] s368;
  wire [75:0] s369;
  reg [37:0] s370;
  reg [37:0] s371;
  reg [37:0] s372;
  wire [37:0] s373;
  reg [37:0] s374;
  reg [37:0] s375;
  reg [37:0] s376;
  wire [37:0] s377;
  wire [37:0] s378;
  reg [37:0] s379;
  reg [37:0] s380;
  reg [37:0] s381;
  wire [37:0] s382;
  reg [1:0] s383;
  reg [33:0] s384;
  reg s386 [3:0];
  wire s385;
  reg [75:0] s388 [3:0]; // synthesis attribute ram_style of s388 is block
  reg [75:0] s387;
  reg [1:0] s389;
  reg [37:0] s390;
  reg [37:0] s391;
  reg [33:0] s392;
  reg [1:0] s394 [11:0];
  wire [1:0] s393;
  wire [37:0] s395;
  wire [1:0] s396;
  reg [37:0] s397;
  reg [37:0] s398;
  reg [37:0] s399;
  reg [37:0] s401;
  reg [37:0] s400;
  reg [37:0] s403;
  reg [37:0] s402;
  wire s404;
  reg [37:0] s405;
  wire [37:0] s406;
  wire [37:0] s407;
  wire [37:0] s408;
  wire [37:0] s409;
  reg [37:0] s410;
  wire [37:0] s411;
  wire [37:0] s412;
  wire [37:0] s413;
  reg [37:0] s415 [2:0];
  wire [37:0] s414;
  reg [37:0] s417 [2:0];
  wire [37:0] s416;
  wire [37:0] s418;
  wire [37:0] s419;
  wire [37:0] s420;
  reg [37:0] s421;
  wire [37:0] s422;
  wire [37:0] s423;
  wire [37:0] s424;
  wire [37:0] s425;
  wire [75:0] s426;
  wire [37:0] s427;
  wire [37:0] s428;
  wire [37:0] s429;
  wire [37:0] s430;
  wire [37:0] s431;
  reg [75:0] s433 [3:0]; // synthesis attribute ram_style of s433 is block
  reg [75:0] s432;
  reg [33:0] s434;
  reg [37:0] s435;
  reg [37:0] s436;
  reg [37:0] s437;
  reg [37:0] s438;
  reg [1:0] s439;
  reg [37:0] s441 [3:0];
  wire [37:0] s440;
  reg [33:0] s442;
  reg [1:0] s443;
  wire [37:0] s444;
  wire [37:0] s445;
  reg [75:0] s447 [3:0]; // synthesis attribute ram_style of s447 is block
  reg [75:0] s446;
  wire [37:0] s448;
  reg [37:0] s449;
  reg [37:0] s450;
  wire [75:0] s451;
  wire [1:0] s452;
  wire [37:0] s453;
  wire [37:0] s454;
  reg [37:0] s455;
  reg s457 [3:0];
  wire s456;
  wire [37:0] s458;
  reg [37:0] s459;
  reg [1:0] s460;
  reg [37:0] s461;
  wire [37:0] s462;
  wire [37:0] s463;
  reg [1:0] s465 [18:0];
  wire [1:0] s464;
  wire [37:0] s466;
  wire [37:0] s467;
  reg [37:0] s468;
  reg [37:0] s469;
  reg [33:0] s470;
  wire [37:0] s471;
  wire [37:0] s472;
  reg [75:0] s473;
  reg [37:0] s474;
  wire [71:0] s475;
  wire [37:0] s476;
  reg [33:0] s477;
  reg [37:0] s478;
  reg [37:0] s479;
  reg [33:0] s480;
  reg [37:0] s482;
  reg [37:0] s481;
  reg [37:0] s484;
  reg [37:0] s483;
  reg [37:0] s485;
  wire [37:0] s486;
  reg [33:0] s487;
  reg [37:0] s488;
  reg [37:0] s489;
  wire [1:0] s490;
  wire [37:0] s491;
  wire [37:0] s492;
  reg [37:0] s493;
  reg [37:0] s494;
  reg [75:0] s496 [3:0]; // synthesis attribute ram_style of s496 is block
  reg [75:0] s495;
  reg [37:0] s497;
  reg [37:0] s498;
  wire [1:0] s499;
  wire [1:0] s500;
  reg [37:0] s501;
  wire [37:0] s502;
  wire [37:0] s503;
  reg [33:0] s504;
  reg [75:0] s506 [3:0]; // synthesis attribute ram_style of s506 is block
  reg [75:0] s505;
  reg [37:0] s507;
  wire [37:0] s508;
  wire [37:0] s509;
  wire [37:0] s510;
  wire [37:0] s511;
  wire [37:0] s512;
  reg [37:0] s514;
  reg [37:0] s513;
  reg [37:0] s516;
  reg [37:0] s515;
  wire [37:0] s517;
  wire [37:0] s518;
  reg [1:0] s519;
  wire [37:0] s520;
  reg [37:0] s521;
  reg [37:0] s523 [2:0];
  wire [37:0] s522;
  reg [75:0] s524;
  reg [33:0] s525;
  wire [37:0] s526;
  wire [37:0] s527;
  reg [37:0] s529;
  reg [37:0] s528;
  reg [37:0] s531;
  reg [37:0] s530;
  reg [37:0] s532;
  reg [37:0] s534 [2:0];
  wire [37:0] s533;
  wire [37:0] s535;
  wire [75:0] s536;
  wire [37:0] s537;
  wire [75:0] s538;
  wire [71:0] s539;
  wire [37:0] s540;
  wire [37:0] s541;
  reg [33:0] s542;
  reg [75:0] s543;
  wire [37:0] s544;
  wire [37:0] s545;
  reg [37:0] s546;
  reg [37:0] s547;
  wire s548;
  integer i;
  assign s1 = s290 ? s254 : s398;
  assign s2 = s290 ? s255 : s399;
  assign s3 = s289 + s477;
  assign s4 = s144 + s222;
  assign s5 = s175 + s478;
  assign s6 = s176 + s479;
  assign s15 = s318[0];
  assign s18 = s228 ? s528 : s9;
  assign s19 = s228 ? s530 : s11;
  assign s22 = {s41, s42};
  assign s24 = s456 ? s86 : s220;
  assign s25 = s26 [3];
  assign s27 = s321 ? s169 : s391;
  assign s28 = s228 ? s513 : s400;
  assign s29 = s228 ? s515 : s402;
  assign s30 = s31 [18];
  assign s33 = s34 [4];
  assign s35 = s36 [4];
  assign s37 = s375 + s147;
  assign s38 = s376 + s148;
  assign s39 = s178 ? s154 : s429;
  assign s40 = s178 ? s155 : s430;
  assign s44 = s289 - s477;
  assign s45 = {s206, s207};
  assign s47 = s48 [8];
  assign s49 = s272 ? s517 : s294;
  assign s50 = s272 ? s518 : s295;
  assign s51 = s331[75:38];
  assign s52 = s331[37:0];
  assign s53 = s54 [37];
  assign s55 = s126 - s60;
  assign s58 = s59 [22];
  assign s60 = s61 [2];
  assign s62 = s63 [2];
  assign s70 = s167 ? s258 : s435;
  assign s71 = s167 ? s259 : s436;
  assign s74 = s91 ? s466 : s256;
  assign s75 = s91 ? s467 : s257;
  assign s76 = s533 - s328;
  assign s78 = s542 - s166;
  assign s79 = s143 + s521;
  assign s83 = s84 [3];
  assign s85 = {s263, s501};
  assign s88 = s83 ? s139 : s261;
  assign s89 = s272 ? s294 : s517;
  assign s90 = s272 ? s295 : s518;
  assign s91 = s92 [6];
  assign s93 = s385 ? s500 : s318;
  assign s95 = s96 [6];
  assign s97 = s83 ? s261 : s139;
  assign s100 = s101 [2];
  assign s102 = s103 [2];
  assign s104 = s83 ? s230 : s180;
  assign o0 = s317;
  assign s105 = $signed(s56) * $signed(s504);
  assign s107 = s46 + s410;
  assign s108 = s439 ^ s203;
  assign s111 = s170 ^ s203;
  assign s114 = s380 - s381;
  assign s116 = s117 [2];
  assign s119 = s66 ? s481 : s132;
  assign s120 = s66 ? s483 : s134;
  assign s121 = s17 + s13;
  assign s124 = s91 ? s208 : s236;
  assign s125 = s91 ? s209 : s237;
  assign s126 = s127 [2];
  assign s128 = s129 [4];
  assign s130 = s131 [4];
  assign s136 = s321 ? s281 : s459;
  assign s137 = s46 - s410;
  assign s138 = {s178, s177};
  assign s140 = s361 ? s138 : s460;
  assign s141 = s142 [11];
  assign s145 = s146 [2];
  assign s151 = s260 + s374;
  assign s152 = $signed(s161) * $signed(34'd3037000499);
  assign s154 = i0[75:38];
  assign s155 = i0[37:0];
  assign s157 = s456 ? s489 : s87;
  assign s160 = s102 ? s153 : s297;
  assign s162 = s83 ? s397 : s449;
  assign s163 = $signed(s8) * $signed(s434);
  assign s164 = s167 ? s497 : s98;
  assign s165 = s167 ? s498 : s99;
  assign s167 = s168 [8];
  assign s171 = s105[69:32];
  assign s177 = s460[1];
  assign s178 = s460[0];
  assign s179 = s308 - s14;
  assign s181 = s102 ? s421 : s32;
  assign s184 = s321 ? s459 : s281;
  assign s190 = {s245, s405};
  assign s191 = s192 [18];
  assign s193 = 38'd0 - s100;
  assign s194 = s167 ? s98 : s497;
  assign s195 = s167 ? s99 : s498;
  assign s196 = s152[69:32];
  assign s197 = s198 [11];
  assign s199 = s250[75:38];
  assign s200 = s250[37:0];
  assign s202 = s456 ? s488 : s186;
  assign s208 = s320[75:38];
  assign s209 = s320[37:0];
  assign s211 = s102 ? s345 : s43;
  assign s212 = s178 ? s377 : s491;
  assign s213 = s178 ? s378 : s492;
  assign s214 = s308 + s14;
  assign s216 = s163[69:32];
  assign s217 = s122[75:38];
  assign s218 = s122[37:0];
  assign s219 = {s507, s356};
  assign s223 = s321 ? s319 : s485;
  assign s224 = s456 ? s186 : s488;
  assign s234 = s235 [11];
  assign s236 = s323[75:38];
  assign s237 = s323[37:0];
  assign s238 = $signed(s7) * $signed(s384);
  assign s239 = s47 ? s267 : s20;
  assign s240 = s47 ? s268 : s21;
  assign s241 = s178 ? s491 : s377;
  assign s242 = s178 ? s492 : s378;
  assign s243 = s244 [18];
  assign s247 = s106 - s185;
  assign s248 = s546 - s547;
  assign s249 = s116 + s370;
  assign s256 = s473[75:38];
  assign s257 = s473[37:0];
  assign s264 = {s158, s159};
  assign s269 = {s390, s532};
  assign s270 = s91 ? s236 : s208;
  assign s271 = s91 ? s237 : s209;
  assign s272 = s273 [6];
  assign s275 = s385 ? s404 : s361;
  assign s279 = s542 + s166;
  assign s280 = $signed(s416) * $signed(s115);
  assign s282 = $signed(s414) * $signed(s487);
  assign s283 = s304 + s355;
  assign s284 = {s182, s183};
  assign s285 = s468 + s188;
  assign s286 = s469 + s189;
  assign s287 = {s253, s455};
  assign s288 = s102 ? s225 : s123;
  assign s293 = s304 - s355;
  assign s294 = s16[75:38];
  assign s295 = s16[37:0];
  assign s300 = s321 ? s274 : s80;
  assign s301 = s456 ? s187 : s221;
  assign s302 = s375 - s147;
  assign s303 = s376 - s148;
  assign s305 = s321 ? s391 : s169;
  assign s311 = s312 [4];
  assign s313 = s522 - s370;
  assign s314 = s456 ? s87 : s489;
  assign s316 = s321 ? s485 : s319;
  assign s317 = {s109, s110};
  assign s321 = s322 [9];
  assign s324 = s102 ? s123 : s225;
  assign s325 = s475[69:32];
  assign o3 = s536;
  assign s326 = s290 ? s251 : s437;
  assign s327 = s290 ? s252 : s438;
  assign s328 = s329 [2];
  assign s330 = s144 - s222;
  assign s333 = s83 ? s180 : s230;
  assign s334 = s66 ? s292 : s372;
  assign s335 = s66 ? s291 : s371;
  assign s338 = s339 [8];
  assign s340 = {s112, s113};
  assign s342 = s62 + s328;
  assign s346 = s15 ? 2'd3 : 2'd0;
  assign s347 = s468 - s188;
  assign s348 = s469 - s189;
  assign s350 = s341[75:38];
  assign s351 = s341[37:0];
  assign s352 = s272 ? s217 : s350;
  assign s353 = s272 ? s218 : s351;
  assign s354 = s362[69:32];
  assign s357 = s102 ? s297 : s153;
  assign s358 = s118[75:38];
  assign s359 = s118[37:0];
  assign s362 = $signed(s57) * $signed(s94);
  assign s363 = s47 ? s81 : s309;
  assign s364 = s47 ? s82 : s310;
  assign s365 = s280[69:32];
  assign s366 = s178 ? s429 : s154;
  assign s367 = s178 ? s430 : s155;
  assign s368 = s102 ? s32 : s421;
  assign s369 = {s210, s174};
  assign s373 = s102 ? s43 : s345;
  assign o2 = s45;
  assign s377 = i1[75:38];
  assign s378 = i1[37:0];
  assign s382 = s539[69:32];
  assign s385 = s386 [3];
  assign s393 = s394 [11];
  assign s395 = s83 ? s450 : s461;
  assign s396 = next ? 2'd0 : s499;
  assign s404 = s361 + 1'd1;
  assign s406 = s321 ? s80 : s274;
  assign s407 = s379 - s360;
  assign s408 = s311 ? s33 : s128;
  assign s409 = s311 ? s35 : s130;
  assign s411 = s167 ? s435 : s258;
  assign s412 = s167 ? s436 : s259;
  assign s413 = s456 ? s221 : s187;
  assign s414 = s415 [2];
  assign s416 = s417 [2];
  assign s418 = s272 ? s350 : s217;
  assign s419 = s272 ? s351 : s218;
  assign s420 = s282[69:32];
  assign s422 = s336 - s337;
  assign s423 = s145 + s60;
  assign s424 = s47 ? s20 : s267;
  assign s425 = s47 ? s21 : s268;
  assign s426 = {s349, s246};
  assign s427 = s95 ? s200 : s359;
  assign s428 = s95 ? s199 : s358;
  assign s429 = i2[75:38];
  assign s430 = i2[37:0];
  assign s431 = s106 + s185;
  assign s440 = s441 [3];
  assign s444 = s290 ? s398 : s254;
  assign s445 = s290 ? s399 : s255;
  assign s448 = s379 + s360;
  assign s451 = {s68, s69};
  assign next_out = s53;
  assign s452 = s383 ^ s203;
  assign s453 = s91 ? s256 : s466;
  assign s454 = s91 ? s257 : s467;
  assign s456 = s457 [3];
  assign s458 = s456 ? s220 : s86;
  assign s462 = s338 ? s72 : s204;
  assign s463 = s338 ? s73 : s205;
  assign s464 = s465 [18];
  assign s466 = s543[75:38];
  assign s467 = s543[37:0];
  assign s471 = s344 - s65;
  assign s472 = s343 - s64;
  assign s475 = $signed(s474) * $signed(s470);
  assign s476 = s83 ? s461 : s450;
  assign s486 = s17 - s13;
  assign s490 = reset ? 2'd0 : s93;
  assign s491 = i3[75:38];
  assign s492 = i3[37:0];
  assign s499 = s460 + 2'd1;
  assign s500 = s318 + 2'd1;
  assign s502 = s47 ? s309 : s81;
  assign s503 = s47 ? s310 : s82;
  assign s508 = s524[75:38];
  assign s509 = s524[37:0];
  assign s510 = s343 + s64;
  assign s511 = s344 + s65;
  assign s512 = s260 - s374;
  assign s517 = s233[75:38];
  assign s518 = s233[37:0];
  assign o1 = s340;
  assign s520 = s238[69:32];
  assign s522 = s523 [2];
  assign s526 = s290 ? s437 : s251;
  assign s527 = s290 ? s438 : s252;
  assign s533 = s534 [2];
  assign s535 = s143 - s521;
  assign s536 = {s493, s494};
  assign s537 = s83 ? s449 : s397;
  assign s538 = {s156, s23};
  assign s539 = $signed(s262) * $signed(s480);
  assign s540 = s175 - s478;
  assign s541 = s176 - s479;
  assign s544 = s95 ? s52 : s509;
  assign s545 = s95 ? s51 : s508;
  assign s548 = reset ? 1'd0 : s275;
  always @(*)
    case(s318)
      0: s77 = 2'd0;
      1: s77 = 2'd1;
      2: s77 = 2'd3;
      3: s77 = 2'd2;
    endcase
  always @(*)
    case(s25)
      0: s215 = s440;
      1: s215 = s201;
      2: s215 = 38'd0;
      3: s215 = s307;
    endcase
  always @(*)
    case(s58)
      0: s276 = 34'd0;
      1: s276 = 34'd3037000499;
      2: s276 = 34'd4294967296;
      3: s276 = 34'd3037000499;
    endcase
  always @(*)
    case(s318)
      0: s296 = 2'd0;
      1: s296 = 2'd2;
      2: s296 = 2'd3;
      3: s296 = 2'd1;
    endcase
  always @(*)
    case(s58)
      0: s306 = 34'd0;
      1: s306 = 34'd13211836807;
      2: s306 = 34'd14142868685;
      3: s306 = 34'd1643612826;
    endcase
  always @(*)
    case(s58)
      0: s315 = 34'd4294967296;
      1: s315 = 34'd3037000499;
      2: s315 = 34'd0;
      3: s315 = 34'd14142868685;
    endcase
  always @(*)
    case(s58)
      0: s392 = 34'd4294967296;
      1: s392 = 34'd3968032377;
      2: s392 = 34'd3037000499;
      3: s392 = 34'd1643612826;
    endcase
  always @(*)
    case(s58)
      0: s442 = 34'd0;
      1: s442 = 34'd1643612826;
      2: s442 = 34'd3037000499;
      3: s442 = 34'd3968032377;
    endcase
  always @(*)
    case(s58)
      0: s525 = 34'd4294967296;
      1: s525 = 34'd1643612826;
      2: s525 = 34'd14142868685;
      3: s525 = 34'd13211836807;
    endcase
  always @(posedge clk)
    begin
      s7 <= s546;
      s8 <= s547;
      s10 <= s199;
      s9 <= s10;
      s12 <= s200;
      s11 <= s12;
      s13 <= s179;
      s14 <= s76;
      s16 <= s265;
      s17 <= s330;
      s20 <= s453;
      s21 <= s454;
      s23 <= s27;
      s26 [0] <= s58;
      for (i = 1; i < 4; i = i + 1)
        s26 [i] <= s26 [i - 1];
      s31 [0] <= s234;
      for (i = 1; i < 19; i = i + 1)
        s31 [i] <= s31 [i - 1];
      s32 <= s448;
      s34 [0] <= s371;
      for (i = 1; i < 5; i = i + 1)
        s34 [i] <= s34 [i - 1];
      s36 [0] <= s372;
      for (i = 1; i < 5; i = i + 1)
        s36 [i] <= s36 [i - 1];
      s41 <= s526;
      s42 <= s527;
      s43 <= s107;
      s46 <= s4;
      s48 [0] <= s178;
      for (i = 1; i < 9; i = i + 1)
        s48 [i] <= s48 [i - 1];
      s54 [0] <= s385;
      for (i = 1; i < 38; i = i + 1)
        s54 [i] <= s54 [i - 1];
      s56 <= s336;
      s57 <= s337;
      s59 [0] <= s460;
      for (i = 1; i < 23; i = i + 1)
        s59 [i] <= s59 [i - 1];
      s61 [0] <= s325;
      for (i = 1; i < 3; i = i + 1)
        s61 [i] <= s61 [i - 1];
      s63 [0] <= s354;
      for (i = 1; i < 3; i = i + 1)
        s63 [i] <= s63 [i - 1];
      s64 <= s363;
      s65 <= s364;
      s67 <= s338;
      s66 <= s67;
      s68 <= s1;
      s69 <= s2;
      s72 <= s427;
      s73 <= s428;
      s80 <= s368;
      s81 <= s270;
      s82 <= s271;
      s84 [0] <= s47;
      for (i = 1; i < 4; i = i + 1)
        s84 [i] <= s84 [i - 1];
      s86 <= s302;
      s87 <= s303;
      s92 [0] <= s290;
      for (i = 1; i < 7; i = i + 1)
        s92 [i] <= s92 [i - 1];
      s94 <= s78;
      s96 [0] <= s83;
      for (i = 1; i < 7; i = i + 1)
        s96 [i] <= s96 [i - 1];
      s98 <= s49;
      s99 <= s50;
      s101 [0] <= s196;
      for (i = 1; i < 3; i = i + 1)
        s101 [i] <= s101 [i - 1];
      s103 [0] <= s311;
      for (i = 1; i < 3; i = i + 1)
        s103 [i] <= s103 [i - 1];
      s106 <= s79;
      s109 <= s70;
      s110 <= s71;
      s112 <= s411;
      s113 <= s412;
      s115 <= s283;
      s117 [0] <= s365;
      for (i = 1; i < 3; i = i + 1)
        s117 [i] <= s117 [i - 1];
      s118 <= s298;
      s122 <= s446;
      s123 <= s407;
      s127 [0] <= s520;
      for (i = 1; i < 3; i = i + 1)
        s127 [i] <= s127 [i - 1];
      s129 [0] <= s291;
      for (i = 1; i < 5; i = i + 1)
        s129 [i] <= s129 [i - 1];
      s131 [0] <= s292;
      for (i = 1; i < 5; i = i + 1)
        s131 [i] <= s131 [i - 1];
      s133 <= s72;
      s132 <= s133;
      s135 <= s73;
      s134 <= s135;
      s139 <= s24;
      s142 [0] <= s443;
      for (i = 1; i < 12; i = i + 1)
        s142 [i] <= s142 [i - 1];
      s143 <= s408;
      s144 <= s409;
      s146 [0] <= s216;
      for (i = 1; i < 3; i = i + 1)
        s146 [i] <= s146 [i - 1];
      s147 <= s471;
      s148 <= s472;
      s150 [s332] <= s284;
      s149 <= s150 [s332];
      s153 <= s137;
      s156 <= s300;
      s158 <= s444;
      s159 <= s445;
      s161 <= s114;
      s166 <= s306;
      s168 [0] <= s102;
      for (i = 1; i < 9; i = i + 1)
        s168 [i] <= s168 [i - 1];
      s169 <= s211;
      s170 <= s346;
      s173 [s141] <= s287;
      s172 <= s173 [s141];
      s174 <= s537;
      s175 <= s424;
      s176 <= s425;
      s180 <= s157;
      s182 <= s326;
      s183 <= s327;
      s185 <= s214;
      s186 <= s37;
      s187 <= s38;
      s188 <= s510;
      s189 <= s511;
      s192 [0] <= s141;
      for (i = 1; i < 19; i = i + 1)
        s192 [i] <= s192 [i - 1];
      s198 [0] <= s389;
      for (i = 1; i < 12; i = i + 1)
        s198 [i] <= s198 [i - 1];
      s201 <= s100;
      s203 <= s140;
      s204 <= s544;
      s205 <= s545;
      s206 <= s194;
      s207 <= s195;
      s210 <= s88;
      s220 <= s285;
      s221 <= s286;
      s222 <= s249;
      s225 <= s431;
      s227 [s519] <= s451;
      s226 <= s227 [s519];
      s229 <= s95;
      s228 <= s229;
      s230 <= s413;
      s232 [s191] <= s85;
      s231 <= s232 [s191];
      s233 <= s387;
      s235 [0] <= s332;
      for (i = 1; i < 12; i = i + 1)
        s235 [i] <= s235 [i - 1];
      s244 [0] <= s197;
      for (i = 1; i < 19; i = i + 1)
        s244 [i] <= s244 [i - 1];
      s245 <= s406;
      s246 <= s104;
      s250 <= s432;
      s251 <= s39;
      s252 <= s40;
      s253 <= s476;
      s254 <= s241;
      s255 <= s242;
      s258 <= s89;
      s259 <= s90;
      s260 <= s423;
      s261 <= s224;
      s262 <= s422;
      s263 <= s184;
      s266 [s30] <= s269;
      s265 <= s266 [s30];
      s267 <= s124;
      s268 <= s125;
      s273 [0] <= s321;
      for (i = 1; i < 7; i = i + 1)
        s273 [i] <= s273 [i - 1];
      s274 <= s324;
      s278 [s443] <= s264;
      s277 <= s278 [s443];
      s281 <= s288;
      s289 <= s392;
      s290 <= s177;
      s291 <= s28;
      s292 <= s29;
      s297 <= s486;
      s299 [s234] <= s426;
      s298 <= s299 [s234];
      s304 <= s315;
      s307 <= s193;
      s308 <= s55;
      s309 <= s74;
      s310 <= s75;
      s312 [0] <= s66;
      for (i = 1; i < 5; i = i + 1)
        s312 [i] <= s312 [i - 1];
      s318 <= s490;
      s319 <= s373;
      s320 <= s495;
      s322 [0] <= s228;
      for (i = 1; i < 10; i = i + 1)
        s322 [i] <= s322 [i - 1];
      s323 <= s226;
      s329 [0] <= s382;
      for (i = 1; i < 3; i = i + 1)
        s329 [i] <= s329 [i - 1];
      s331 <= s505;
      s332 <= s111;
      s336 <= s119;
      s337 <= s120;
      s339 [0] <= s456;
      for (i = 1; i < 9; i = i + 1)
        s339 [i] <= s339 [i - 1];
      s341 <= s231;
      s343 <= s502;
      s344 <= s503;
      s345 <= s121;
      s349 <= s395;
      s355 <= s276;
      s356 <= s162;
      s360 <= s512;
      s361 <= s548;
      s370 <= s215;
      s371 <= s18;
      s372 <= s19;
      s374 <= s342;
      s375 <= s540;
      s376 <= s541;
      s379 <= s535;
      s380 <= s462;
      s381 <= s463;
      s383 <= s77;
      s384 <= s44;
      s386 [0] <= next;
      for (i = 1; i < 4; i = i + 1)
        s386 [i] <= s386 [i - 1];
      s388 [s243] <= s538;
      s387 <= s388 [s243];
      s389 <= s452;
      s390 <= s136;
      s391 <= s357;
      s394 [0] <= s519;
      for (i = 1; i < 12; i = i + 1)
        s394 [i] <= s394 [i - 1];
      s397 <= s314;
      s398 <= s366;
      s399 <= s367;
      s401 <= s51;
      s400 <= s401;
      s403 <= s52;
      s402 <= s403;
      s405 <= s305;
      s410 <= s151;
      s415 [0] <= s380;
      for (i = 1; i < 3; i = i + 1)
        s415 [i] <= s415 [i - 1];
      s417 [0] <= s381;
      for (i = 1; i < 3; i = i + 1)
        s417 [i] <= s417 [i - 1];
      s421 <= s247;
      s433 [s197] <= s369;
      s432 <= s433 [s197];
      s434 <= s3;
      s435 <= s418;
      s436 <= s419;
      s437 <= s212;
      s438 <= s213;
      s439 <= s296;
      s441 [0] <= s161;
      for (i = 1; i < 4; i = i + 1)
        s441 [i] <= s441 [i - 1];
      s443 <= s108;
      s447 [s464] <= s190;
      s446 <= s447 [s464];
      s449 <= s301;
      s450 <= s458;
      s455 <= s333;
      s457 [0] <= s91;
      for (i = 1; i < 4; i = i + 1)
        s457 [i] <= s457 [i - 1];
      s459 <= s181;
      s460 <= s396;
      s461 <= s202;
      s465 [0] <= s393;
      for (i = 1; i < 19; i = i + 1)
        s465 [i] <= s465 [i - 1];
      s468 <= s5;
      s469 <= s6;
      s470 <= s289;
      s473 <= s149;
      s474 <= s248;
      s477 <= s442;
      s478 <= s239;
      s479 <= s240;
      s480 <= s542;
      s482 <= s204;
      s481 <= s482;
      s484 <= s205;
      s483 <= s484;
      s485 <= s160;
      s487 <= s293;
      s488 <= s347;
      s489 <= s348;
      s493 <= s164;
      s494 <= s165;
      s496 [s389] <= s22;
      s495 <= s496 [s389];
      s497 <= s352;
      s498 <= s353;
      s501 <= s316;
      s504 <= s279;
      s506 [s393] <= s219;
      s505 <= s506 [s393];
      s507 <= s97;
      s514 <= s508;
      s513 <= s514;
      s516 <= s509;
      s515 <= s516;
      s519 <= s203;
      s521 <= s313;
      s523 [0] <= s420;
      for (i = 1; i < 3; i = i + 1)
        s523 [i] <= s523 [i - 1];
      s524 <= s172;
      s529 <= s358;
      s528 <= s529;
      s531 <= s359;
      s530 <= s531;
      s532 <= s223;
      s534 [0] <= s171;
      for (i = 1; i < 3; i = i + 1)
        s534 [i] <= s534 [i - 1];
      s542 <= s525;
      s543 <= s277;
      s546 <= s334;
      s547 <= s335;
    end
endmodule
