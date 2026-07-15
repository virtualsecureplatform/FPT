/*
 * This file was generated using SGen v.26.7.15.
 *    _____ ______          SGen v.26.7.15 - A Generator of Streaming Hardware
 *   / ___// ____/__  ____  Department of Computer Science, ETH Zurich, Switzerland
 *   \__ \/ / __/ _ \/ __ \
 *  ___/ / /_/ /  __/ / / / Copyright (C) 2020-2025 François Serre (serref@inf.ethz.ch)
 * /____/\____/\___/_/ /_/  https://github.com/fserre/sgen
 *
 * This design operates on datasets of 16 elements, streamed over 8 cycles of 2 elements. This means that
 * each dataset has to be split into chunks of 2 elements that are input sequentially every cycle.
 * It has a latency of 61 cycles: the output will begin 61 cycles after the input has begun.
 * It supports full-throughput, which means that a new dataset may be input immediately after the previous one.
 * In total, this design can therefore perform a new transformation every 8 cycles.
 * As single RAM control is used, there must be precisely 8 cycles between datasets.
 * Otherwise, at least 8 additional cycles must be added to ensure that the first output dataset does not get corrupted.
 * Use the -dualRAMcontrol option to use dual control memory instead (use more resources).
 *
 * The interface works as follows:
 * - clk: The input clock, a cycle begins on ascending edge.
 * - reset: Reset signal. It has to be set to 1 during at least one cycle before the first input, and to 0 afterwards.
 * - next: Signals the arrival of the next dataset. It has to be set high 4 cycles before the first inputs enter, and left at 0 otherwise.
 *         Particularly, it should not be set to 1 more than once every 8 cycles, as it might leave the design in a
 *         confused state, that can only be recovered with a reset.
 * - next_out: Indicates that a new dataset begins to output.
 * - i0 - i1: The components of the chunks of the dataset. Each of these elements is a complex number in cartesian form (real and imaginary part are concatenated, each being a signed fixed-point number (27. 14 bits representation)).
 * - o0 - o1: The components of the chunks of the output dataset.
 *
 * If you would like to refer to this design, the following publications describe the methods used for its generation:
 * - Generator: F. Serre and M. Püschel, DSL-Based Hardware Generation with Scala: Example Fast Fourier Transforms and Sorting Networks, TRETS, 2019
 * - Streaming permutations: F. Serre, T. Holenstein and M. Püschel, Optimal Circuits for Streamed Linear Permutations using RAM, Proc. FPGA, pp. 215-223, 2016
 * - Algorithm folding: P. A. Milder, F. Franchetti, J. C. Hoe, and M. Püschel, Computer Generation of Hardware for Linear Digital Signal Processing Transforms, ACM TODAES, Vol. 17, No. 2, 2012
 * - Compact designs: F. Serre and M. Püschel, Memory-Efficient Fast Fourier Transform on Streaming Data by Fusing Permutations, Proc. FPGA, pp. 219-228, 2018
 * - Floating point arithmetic: F. de Dinechin and B. Pasca, Designing custom arithmetic data paths with FloPoCo, IEEE Design & Test of Computers, 28(4):18--27, 2011
 *
 */

module FptSGenInverseGuarded16x2(input clk,
  input reset,
  input next,
  input [81:0] i0,
  input [81:0] i1,
  output next_out,
  output [81:0] o0,
  output [81:0] o1);

  wire [40:0] s1;
  reg [36:0] s3 [30:0];
  wire [36:0] s2;
  wire [36:0] s4;
  reg [1:0] s6 [19:0];
  wire [1:0] s5;
  reg [40:0] s7;
  wire [40:0] s8;
  wire [40:0] s9;
  wire [40:0] s10;
  wire [40:0] s11;
  reg [40:0] s12;
  reg [40:0] s13;
  reg [81:0] s14;
  wire [81:0] s15;
  reg [40:0] s16;
  reg [40:0] s17;
  reg s19 [13:0];
  wire s18;
  reg [40:0] s21 [2:0];
  wire [40:0] s20;
  reg [2:0] s22;
  reg s23;
  reg [36:0] s24;
  reg [40:0] s26;
  reg [40:0] s25;
  reg [40:0] s28;
  reg [40:0] s27;
  wire [40:0] s29;
  wire [40:0] s30;
  wire [40:0] s31;
  reg [40:0] s32;
  wire [40:0] s33;
  reg [40:0] s34;
  wire [81:0] s35;
  wire [40:0] s36;
  wire [40:0] s37;
  reg [40:0] s39;
  reg [40:0] s38;
  reg [40:0] s40;
  wire [36:0] s41;
  reg [81:0] s43 [7:0]; // synthesis attribute ram_style of s43 is block
  reg [81:0] s42;
  reg [2:0] s44;
  wire [40:0] s45;
  wire [40:0] s46;
  wire [40:0] s47;
  wire [40:0] s48;
  wire [40:0] s49;
  wire [40:0] s50;
  reg s52 [18:0];
  wire s51;
  reg [40:0] s53;
  wire s54;
  wire [1:0] s55;
  wire [77:0] s56;
  reg [2:0] s57;
  wire [40:0] s58;
  wire [40:0] s59;
  wire [40:0] s60;
  reg [40:0] s61;
  reg [40:0] s62;
  wire [40:0] s63;
  wire [40:0] s64;
  reg [40:0] s65;
  reg [40:0] s66;
  reg [40:0] s67;
  reg [40:0] s68;
  reg [40:0] s69;
  wire [40:0] s70;
  reg [36:0] s71;
  wire [40:0] s72;
  wire [36:0] s73;
  reg [81:0] s74;
  reg [40:0] s75;
  reg [40:0] s76;
  wire [36:0] s77;
  wire [2:0] s78;
  wire [40:0] s79;
  wire [40:0] s80;
  wire [40:0] s81;
  wire [40:0] s82;
  reg [36:0] s84 [30:0];
  wire [36:0] s83;
  reg s86;
  reg s85;
  reg [2:0] s88 [28:0];
  wire [2:0] s87;
  reg [36:0] s89;
  wire [40:0] s90;
  wire [40:0] s91;
  wire [40:0] s92;
  wire [40:0] s93;
  wire [2:0] s94;
  reg [40:0] s96 [3:0];
  wire [40:0] s95;
  reg [40:0] s98 [3:0];
  wire [40:0] s97;
  reg [40:0] s100;
  reg [40:0] s99;
  reg [40:0] s102;
  reg [40:0] s101;
  reg [40:0] s103;
  wire [40:0] s104;
  wire [40:0] s105;
  reg [40:0] s106;
  wire [81:0] s107;
  reg [40:0] s108;
  reg [40:0] s110 [3:0];
  wire [40:0] s109;
  reg [2:0] s112;
  reg [2:0] s111;
  wire [36:0] s113;
  reg [36:0] s114;
  reg [2:0] s115;
  wire [81:0] s116;
  reg s118 [2:0];
  wire s117;
  reg [81:0] s119;
  reg [40:0] s121 [2:0];
  wire [40:0] s120;
  reg [40:0] s123 [2:0];
  wire [40:0] s122;
  wire [40:0] s124;
  wire [40:0] s125;
  reg s127;
  reg s126;
  reg [40:0] s128;
  reg [40:0] s129;
  reg s131 [7:0];
  wire s130;
  wire [77:0] s132;
  wire [40:0] s133;
  wire [77:0] s134;
  reg [40:0] s136;
  reg [40:0] s135;
  reg [40:0] s138;
  reg [40:0] s137;
  reg [40:0] s139;
  reg [40:0] s141;
  reg [40:0] s140;
  reg [40:0] s142;
  wire [40:0] s143;
  wire [40:0] s144;
  reg [40:0] s146;
  reg [40:0] s145;
  reg [40:0] s148;
  reg [40:0] s147;
  wire [40:0] s149;
  wire [40:0] s150;
  reg [40:0] s151;
  wire [40:0] s152;
  reg [40:0] s154 [2:0];
  wire [40:0] s153;
  reg [40:0] s155;
  reg [2:0] s156;
  reg [36:0] s157;
  reg [1:0] s158;
  reg [2:0] s160;
  reg [2:0] s159;
  reg [40:0] s161;
  wire [2:0] s162;
  reg [40:0] s163;
  reg [40:0] s164;
  wire [77:0] s165;
  reg [40:0] s166;
  reg [2:0] s167;
  reg [40:0] s169 [3:0];
  wire [40:0] s168;
  reg [40:0] s171 [3:0];
  wire [40:0] s170;
  reg [40:0] s172;
  reg [40:0] s173;
  reg [40:0] s174;
  reg s176 [48:0];
  wire s175;
  reg [2:0] s178;
  reg [2:0] s177;
  wire [81:0] s179;
  reg [36:0] s180;
  wire [40:0] s181;
  reg [2:0] s182;
  reg [40:0] s183;
  wire [40:0] s184;
  reg [40:0] s185;
  reg [40:0] s186;
  wire [40:0] s187;
  reg [36:0] s188;
  reg [40:0] s189;
  wire [81:0] s190;
  wire [40:0] s191;
  reg [2:0] s193 [11:0];
  wire [2:0] s192;
  reg [81:0] s195 [7:0]; // synthesis attribute ram_style of s195 is block
  reg [81:0] s194;
  wire [77:0] s196;
  wire [40:0] s197;
  wire [40:0] s198;
  reg [40:0] s199;
  reg [36:0] s200;
  reg s201;
  reg [81:0] s202;
  wire [40:0] s203;
  reg [40:0] s205;
  reg [40:0] s204;
  reg [2:0] s207 [7:0];
  wire [2:0] s206;
  wire [40:0] s208;
  wire [36:0] s209;
  wire [40:0] s210;
  reg [40:0] s211;
  reg [40:0] s213 [2:0];
  wire [40:0] s212;
  reg [40:0] s214;
  reg [40:0] s215;
  reg [40:0] s217;
  reg [40:0] s216;
  reg s219 [28:0];
  wire s218;
  wire [36:0] s220;
  reg [40:0] s222 [2:0];
  wire [40:0] s221;
  reg [40:0] s223;
  reg [36:0] s224;
  reg [2:0] s226;
  reg [2:0] s225;
  reg [36:0] s227;
  wire [40:0] s228;
  reg [40:0] s230 [2:0];
  wire [40:0] s229;
  wire [77:0] s231;
  wire [40:0] s232;
  reg [40:0] s233;
  reg [40:0] s234;
  reg [40:0] s235;
  reg [40:0] s237 [2:0];
  wire [40:0] s236;
  reg [2:0] s238;
  reg [2:0] s239;
  wire [40:0] s240;
  wire [40:0] s241;
  wire [40:0] s242;
  reg [40:0] s243;
  reg [40:0] s244;
  reg [40:0] s245;
  wire [40:0] s246;
  wire [40:0] s247;
  wire [40:0] s248;
  reg [2:0] s250 [28:0];
  wire [2:0] s249;
  reg [40:0] s251;
  reg [40:0] s252;
  reg [36:0] s253;
  wire s254;
  wire s255;
  wire [1:0] s256;
  wire [1:0] s257;
  reg [81:0] s259;
  reg [81:0] s258;
  reg [40:0] s260;
  reg [2:0] s262 [3:0];
  wire [2:0] s261;
  wire [81:0] s263;
  reg [40:0] s264;
  reg [40:0] s265;
  wire [40:0] s266;
  reg [36:0] s267;
  wire [40:0] s268;
  reg [40:0] s270;
  reg [40:0] s269;
  reg [40:0] s272;
  reg [40:0] s271;
  wire [40:0] s273;
  reg [36:0] s274;
  reg [36:0] s275;
  reg [40:0] s276;
  reg [40:0] s277;
  wire [40:0] s278;
  wire [40:0] s279;
  reg [81:0] s281;
  reg [81:0] s280;
  wire s282;
  wire [40:0] s283;
  wire [40:0] s284;
  wire [40:0] s285;
  wire [81:0] s286;
  wire [81:0] s287;
  wire [81:0] s288;
  wire [40:0] s289;
  wire [40:0] s290;
  wire [40:0] s291;
  wire [40:0] s292;
  reg [81:0] s294 [7:0]; // synthesis attribute ram_style of s294 is block
  reg [81:0] s293;
  reg [81:0] s295;
  reg [81:0] s296;
  wire s297;
  reg s299 [7:0];
  wire s298;
  wire [40:0] s300;
  wire [1:0] s301;
  wire [40:0] s302;
  reg [40:0] s304 [2:0];
  wire [40:0] s303;
  reg [2:0] s305;
  wire [2:0] s306;
  wire [2:0] s307;
  reg [2:0] s308;
  reg [40:0] s309;
  reg [40:0] s310;
  wire [2:0] s311;
  reg s313 [30:0];
  wire s312;
  reg [2:0] s314;
  wire [40:0] s315;
  wire [40:0] s316;
  reg [36:0] s317;
  reg [36:0] s318;
  wire [81:0] s319;
  reg s321 [28:0];
  wire s320;
  wire [40:0] s322;
  wire [40:0] s323;
  wire [40:0] s324;
  wire [40:0] s325;
  wire [40:0] s326;
  wire [40:0] s327;
  wire [81:0] s328;
  wire [40:0] s329;
  reg [36:0] s330;
  wire s331;
  reg [40:0] s332;
  wire [40:0] s333;
  wire [40:0] s334;
  reg s336;
  reg s335;
  reg [2:0] s338 [13:0];
  wire [2:0] s337;
  reg [81:0] s340 [7:0]; // synthesis attribute ram_style of s340 is block
  reg [81:0] s339;
  wire [77:0] s341;
  reg [2:0] s343;
  reg [2:0] s342;
  reg [40:0] s344;
  reg [40:0] s345;
  reg [40:0] s346;
  wire [40:0] s347;
  reg [40:0] s349;
  reg [40:0] s348;
  wire [40:0] s350;
  wire [40:0] s351;
  wire [81:0] s352;
  wire [40:0] s353;
  reg [40:0] s354;
  wire [1:0] s355;
  wire [40:0] s356;
  reg [40:0] s357;
  reg [40:0] s358;
  wire s359;
  reg [2:0] s361 [19:0];
  wire [2:0] s360;
  reg [2:0] s363 [19:0];
  wire [2:0] s362;
  wire [36:0] s364;
  reg [40:0] s366 [3:0];
  wire [40:0] s365;
  reg [40:0] s367;
  wire [81:0] s368;
  wire [40:0] s369;
  wire [2:0] s370;
  wire [40:0] s371;
  wire [40:0] s372;
  reg s373;
  reg [40:0] s375 [3:0];
  wire [40:0] s374;
  reg [40:0] s377 [3:0];
  wire [40:0] s376;
  wire [40:0] s378;
  wire [40:0] s379;
  wire [40:0] s380;
  wire [40:0] s381;
  wire [40:0] s382;
  wire [36:0] s383;
  reg [40:0] s384;
  reg s386;
  reg s385;
  reg [40:0] s387;
  reg [40:0] s388;
  reg s389;
  wire [36:0] s390;
  reg [40:0] s391;
  reg [40:0] s393 [3:0];
  wire [40:0] s392;
  wire [2:0] s394;
  wire [2:0] s395;
  reg [81:0] s397 [7:0]; // synthesis attribute ram_style of s397 is block
  reg [81:0] s396;
  wire [40:0] s398;
  wire [40:0] s399;
  reg [40:0] s400;
  reg [40:0] s401;
  wire [77:0] s402;
  reg s404;
  reg s403;
  reg [2:0] s405;
  reg [40:0] s407 [2:0];
  wire [40:0] s406;
  reg [2:0] s408;
  wire [40:0] s409;
  reg [40:0] s411 [2:0];
  wire [40:0] s410;
  reg [81:0] s413 [7:0]; // synthesis attribute ram_style of s413 is block
  reg [81:0] s412;
  reg [40:0] s414;
  reg [40:0] s415;
  reg [40:0] s416;
  wire [40:0] s417;
  wire [40:0] s418;
  reg s420 [10:0];
  wire s419;
  wire [40:0] s421;
  wire [40:0] s422;
  wire [40:0] s423;
  reg [40:0] s424;
  reg [40:0] s425;
  wire [40:0] s426;
  wire [40:0] s427;
  reg [36:0] s428;
  reg s430 [30:0];
  wire s429;
  wire [77:0] s431;
  reg s433 [3:0];
  wire s432;
  wire [40:0] s434;
  reg [40:0] s435;
  reg [40:0] s436;
  reg [40:0] s437;
  reg [40:0] s438;
  reg [40:0] s439;
  wire [77:0] s440;
  reg [40:0] s441;
  wire [1:0] s442;
  reg [2:0] s444;
  reg [2:0] s443;
  wire [40:0] s445;
  wire [40:0] s446;
  wire [40:0] s447;
  reg [81:0] s448;
  reg [40:0] s450 [2:0];
  wire [40:0] s449;
  reg s452 [3:0];
  wire s451;
  reg [40:0] s453;
  wire [2:0] s454;
  reg [40:0] s455;
  reg [40:0] s456;
  reg [2:0] s458 [19:0];
  wire [2:0] s457;
  reg [40:0] s460;
  reg [40:0] s459;
  reg [40:0] s461;
  reg [81:0] s462;
  reg [40:0] s463;
  reg [81:0] s465;
  reg [81:0] s464;
  reg [81:0] s466;
  reg s468 [3:0];
  wire s467;
  reg [36:0] s469;
  integer i;
  assign s1 = s23 ? s161 : s334;
  assign s2 = s3 [30];
  assign s4 = s274 + s318;
  assign s5 = s6 [19];
  assign s8 = s218 ? s197 : s284;
  assign s9 = s218 ? s198 : s285;
  assign s10 = s467 ? s95 : s392;
  assign s11 = s223 - s211;
  assign s15 = {s128, s129};
  assign s18 = s19 [13];
  assign s20 = s21 [2];
  assign s29 = s56[75:35];
  assign s30 = s117 ? s350 : s149;
  assign s31 = s117 ? s351 : s150;
  assign s33 = s23 ? s151 : s333;
  assign s35 = {s260, s7};
  assign s36 = s297 ? s315 : s421;
  assign s37 = s297 ? s316 : s422;
  assign s41 = s200 - s180;
  assign s45 = s74[40:0];
  assign s46 = s74[81:41];
  assign s47 = s40 + s391;
  assign s48 = s32 + s164;
  assign s49 = s441 - s166;
  assign s50 = s20 - s388;
  assign s51 = s52 [18];
  assign s54 = s158 == 2'd2;
  assign s55 = s130 ? s355 : s158;
  assign s56 = $signed(s456) * $signed(s71);
  assign s58 = s51 ? s66 : s233;
  assign s59 = s335 ? s135 : s25;
  assign s60 = s335 ? s137 : s27;
  assign s63 = s18 ? s436 : s251;
  assign s64 = s18 ? s437 : s252;
  assign s70 = s229 - s453;
  assign s72 = s32 - s164;
  assign s73 = s200 + s180;
  assign s77 = s274 - s318;
  assign s78 = reset ? 3'd0 : s94;
  assign s79 = s85 ? s38 : s204;
  assign s80 = s385 ? s13 : s332;
  assign s81 = s320 ? s45 : s326;
  assign s82 = s320 ? s46 : s327;
  assign s83 = s84 [30];
  assign s87 = s88 [28];
  assign s90 = s40 - s391;
  assign s91 = s431[75:35];
  assign s92 = s373 ? s17 : s447;
  assign s93 = s373 ? s16 : s446;
  assign s94 = s130 ? s311 : s156;
  assign s95 = s96 [3];
  assign s97 = s98 [3];
  assign s104 = s432 ? 41'd0 : s163;
  assign s105 = s309 + s384;
  assign s107 = s335 ? s464 : s319;
  assign s109 = s110 [3];
  assign s113 = s201 ? 37'd34359738368 : 37'd0;
  assign s116 = {s172, s173};
  assign s117 = s118 [2];
  assign s120 = s121 [2];
  assign s122 = s123 [2];
  assign s124 = s406 + s453;
  assign s125 = s419 ? s199 : s354;
  assign s130 = s131 [7];
  assign s132 = $signed(s245) * $signed(37'd24296003999);
  assign s133 = s75 - s277;
  assign s134 = $signed(s461) * $signed(s89);
  assign s143 = s320 ? s326 : s45;
  assign s144 = s320 ? s327 : s46;
  assign s149 = i0[40:0];
  assign s150 = i0[81:41];
  assign s152 = 41'd0 - s449;
  assign s153 = s154 [2];
  assign s162 = next ? 3'd0 : s394;
  assign s165 = $signed(s122) * $signed(s157);
  assign o1 = s15;
  assign s168 = s169 [3];
  assign s170 = s171 [3];
  assign s175 = s176 [48];
  assign s179 = {s68, s69};
  assign s181 = s85 ? s459 : s140;
  assign s184 = s389 ? s276 : s242;
  assign s187 = s165[75:35];
  assign s190 = s126 ? s258 : s263;
  assign s191 = s153 - s212;
  assign s192 = s193 [11];
  assign s196 = $signed(s67) * $signed(s253);
  assign s197 = s202[40:0];
  assign s198 = s202[81:41];
  assign s203 = s231[75:35];
  assign s206 = s207 [7];
  assign s208 = s440[75:35];
  assign s209 = s201 ? 37'd0 : 37'd34359738368;
  assign s210 = s23 ? s334 : s161;
  assign s212 = s213 [2];
  assign s218 = s219 [28];
  assign s220 = s2 + s83;
  assign s221 = s222 [2];
  assign s228 = s310 + s435;
  assign s229 = s230 [2];
  assign s231 = $signed(s234) * $signed(s24);
  assign s232 = s389 ? s235 : s241;
  assign s236 = s237 [2];
  assign s240 = s132[75:35];
  assign s241 = s462[81:41];
  assign s242 = s462[40:0];
  assign s246 = s214 + s424;
  assign s247 = s215 + s425;
  assign s248 = s467 ? s97 : s109;
  assign s249 = s250 [28];
  assign s254 = s408[2];
  assign s255 = s408[0];
  assign s256 = s408[2:1];
  assign s257 = s408[1:0];
  assign s261 = s262 [3];
  assign s263 = {s367, s358};
  assign s266 = s309 - s384;
  assign s268 = s236 - s346;
  assign s273 = s419 ? s387 : s142;
  assign s278 = s221 + s388;
  assign s279 = s196[75:35];
  assign s282 = s298 ? 1'd0 : s359;
  assign s283 = s312 ? s416 : s34;
  assign s284 = s466[40:0];
  assign s285 = s466[81:41];
  assign s286 = {s139, s463};
  assign s287 = {s415, s357};
  assign s288 = {s185, s186};
  assign s289 = s451 ? s374 : s168;
  assign s290 = s451 ? s376 : s170;
  assign s291 = s61 - s62;
  assign s292 = s429 ? 41'd0 : s103;
  assign s297 = s192[1];
  assign s298 = s299 [7];
  assign s300 = s312 ? s183 : s189;
  assign s301 = reset ? 2'd0 : s55;
  assign s302 = s51 ? s12 : s108;
  assign s303 = s304 [2];
  assign s306 = {s255, s256};
  assign s307 = {s257, s254};
  assign s311 = s331 ? 3'd0 : s395;
  assign s312 = s313 [30];
  assign s315 = s295[81:41];
  assign s316 = s295[40:0];
  assign s319 = {s344, s345};
  assign s320 = s321 [28];
  assign s322 = s403 ? s145 : s99;
  assign s323 = s403 ? s147 : s101;
  assign s324 = s310 - s435;
  assign s325 = s23 ? s333 : s151;
  assign s326 = s448[40:0];
  assign s327 = s448[81:41];
  assign s328 = s85 ? s280 : s368;
  assign s329 = s126 ? s269 : s271;
  assign s331 = s156 == 3'd5;
  assign s333 = s14[81:41];
  assign s334 = s14[40:0];
  assign s337 = s338 [13];
  assign s341 = $signed(s120) * $signed(s469);
  assign s347 = s126 ? s348 : s216;
  assign s350 = i1[40:0];
  assign s351 = i1[81:41];
  assign s352 = {s53, s106};
  assign s353 = s441 + s166;
  assign s355 = s54 ? 2'd0 : s442;
  assign s356 = s312 ? s34 : s416;
  assign s359 = s201 + 1'd1;
  assign s360 = s361 [19];
  assign s362 = s363 [19];
  assign s364 = s330 - s267;
  assign s365 = s366 [3];
  assign s368 = {s400, s65};
  assign s369 = s419 ? s354 : s199;
  assign s370 = s167 ^ s115;
  assign s371 = s402[75:35];
  assign s372 = s410 + s346;
  assign s374 = s375 [3];
  assign s376 = s377 [3];
  assign s378 = s297 ? s421 : s315;
  assign s379 = s297 ? s422 : s316;
  assign s380 = s134[75:35];
  assign s381 = s75 + s277;
  assign s382 = s243 - s244;
  assign s383 = s330 + s267;
  assign s390 = s2 - s83;
  assign s392 = s393 [3];
  assign s394 = s408 + 3'd1;
  assign s395 = s156 + 3'd1;
  assign s398 = s117 ? s149 : s350;
  assign s399 = s117 ? s150 : s351;
  assign o0 = s116;
  assign s402 = $signed(s455) * $signed(s428);
  assign s406 = s407 [2];
  assign s409 = s419 ? s142 : s387;
  assign next_out = s175;
  assign s410 = s411 [2];
  assign s417 = s264 - s265;
  assign s418 = s341[75:35];
  assign s419 = s420 [10];
  assign s421 = s119[81:41];
  assign s422 = s119[40:0];
  assign s423 = s385 ? s174 : s76;
  assign s426 = s214 - s424;
  assign s427 = s215 - s425;
  assign s429 = s430 [30];
  assign s431 = $signed(s439) * $signed(s317);
  assign s432 = s433 [3];
  assign s434 = s312 ? s189 : s183;
  assign s440 = $signed(s438) * $signed(s227);
  assign s442 = s158 + 2'd1;
  assign s445 = s303 + s212;
  assign s446 = s296[81:41];
  assign s447 = s296[40:0];
  assign s449 = s450 [2];
  assign s451 = s452 [3];
  assign s454 = s44 ^ s57;
  assign s457 = s458 [19];
  assign s467 = s468 [3];
  always @(*)
    case(s457)
      0: s22 = 3'd0;
      1: s22 = 3'd2;
      2: s22 = 3'd3;
      3: s22 = 3'd7;
      4: s22 = 3'd5;
      default: s22 = 3'd4;
    endcase
  always @(*)
    case(s337)
      0: s114 = 37'd0;
      1: s114 = 37'd0;
      2: s114 = 37'd0;
      3: s114 = 37'd24296003999;
      4: s114 = 37'd0;
      5: s114 = 37'd34359738368;
      6: s114 = 37'd0;
      7: s114 = 37'd24296003999;
    endcase
  always @(*)
    case(s261)
      0: s155 = s365;
      1: s155 = s365;
      2: s155 = s365;
      3: s155 = s414;
      4: s155 = s365;
      5: s155 = 41'd0;
      6: s155 = s365;
      7: s155 = s401;
    endcase
  always @(*)
    case(s5)
      0: s182 = s206;
      1: s182 = s360;
      default: s182 = s362;
    endcase
  always @(*)
    case(s337)
      0: s188 = 37'd34359738368;
      1: s188 = 37'd34359738368;
      2: s188 = 37'd31744259020;
      3: s188 = 37'd13148902613;
      4: s188 = 37'd24296003999;
      5: s188 = 37'd113142949473;
      6: s188 = 37'd13148902613;
      7: s188 = 37'd105694694452;
    endcase
  always @(*)
    case(s337)
      0: s224 = 37'd34359738368;
      1: s224 = 37'd34359738368;
      2: s224 = 37'd34359738368;
      3: s224 = 37'd24296003999;
      4: s224 = 37'd34359738368;
      5: s224 = 37'd0;
      6: s224 = 37'd34359738368;
      7: s224 = 37'd113142949473;
    endcase
  always @(*)
    case(s156)
      0: s238 = 3'd0;
      1: s238 = 3'd4;
      2: s238 = 3'd5;
      3: s238 = 3'd7;
      4: s238 = 3'd3;
      default: s238 = 3'd2;
    endcase
  always @(*)
    case(s337)
      0: s275 = 37'd0;
      1: s275 = 37'd0;
      2: s275 = 37'd124290050859;
      3: s275 = 37'd105694694452;
      4: s275 = 37'd113142949473;
      5: s275 = 37'd113142949473;
      6: s275 = 37'd105694694452;
      7: s275 = 37'd13148902613;
    endcase
  always @(*)
    case(s158)
      0: s314 = s408;
      1: s314 = s307;
      default: s314 = s306;
    endcase
  always @(posedge clk)
    begin
      s3 [0] <= s274;
      for (i = 1; i < 31; i = i + 1)
        s3 [i] <= s3 [i - 1];
      s6 [0] <= s158;
      for (i = 1; i < 20; i = i + 1)
        s6 [i] <= s6 [i - 1];
      s7 <= s125;
      s12 <= s278;
      s13 <= s353;
      s14 <= s190;
      s16 <= s59;
      s17 <= s60;
      s19 [0] <= s255;
      for (i = 1; i < 14; i = i + 1)
        s19 [i] <= s19 [i - 1];
      s21 [0] <= s418;
      for (i = 1; i < 3; i = i + 1)
        s21 [i] <= s21 [i - 1];
      s23 <= s126;
      s24 <= s220;
      s26 <= s436;
      s25 <= s26;
      s28 <= s437;
      s27 <= s28;
      s32 <= s210;
      s34 <= s47;
      s39 <= s332;
      s38 <= s39;
      s40 <= s248;
      s43 [s342] <= s287;
      s42 <= s43 [s308];
      s44 <= s22;
      s52 [0] <= s451;
      for (i = 1; i < 19; i = i + 1)
        s52 [i] <= s52 [i - 1];
      s53 <= s434;
      s57 <= s182;
      s61 <= s322;
      s62 <= s323;
      s65 <= s423;
      s66 <= s50;
      s67 <= s291;
      s68 <= s398;
      s69 <= s399;
      s71 <= s4;
      s74 <= s396;
      s75 <= s10;
      s76 <= s72;
      s84 [0] <= s318;
      for (i = 1; i < 31; i = i + 1)
        s84 [i] <= s84 [i - 1];
      s86 <= s385;
      s85 <= s86;
      s88 [0] <= s111;
      for (i = 1; i < 29; i = i + 1)
        s88 [i] <= s88 [i - 1];
      s89 <= s390;
      s96 [0] <= s241;
      for (i = 1; i < 4; i = i + 1)
        s96 [i] <= s96 [i - 1];
      s98 [0] <= s242;
      for (i = 1; i < 4; i = i + 1)
        s98 [i] <= s98 [i - 1];
      s100 <= s197;
      s99 <= s100;
      s102 <= s198;
      s101 <= s102;
      s103 <= s11;
      s106 <= s283;
      s108 <= s445;
      s110 [0] <= s276;
      for (i = 1; i < 4; i = i + 1)
        s110 [i] <= s110 [i - 1];
      s112 <= s239;
      s111 <= s112;
      s115 <= s314;
      s118 [0] <= s254;
      for (i = 1; i < 3; i = i + 1)
        s118 [i] <= s118 [i - 1];
      s119 <= s339;
      s121 [0] <= s243;
      for (i = 1; i < 3; i = i + 1)
        s121 [i] <= s121 [i - 1];
      s123 [0] <= s244;
      for (i = 1; i < 3; i = i + 1)
        s123 [i] <= s123 [i - 1];
      s127 <= s51;
      s126 <= s127;
      s128 <= s81;
      s129 <= s82;
      s131 [0] <= next;
      for (i = 1; i < 8; i = i + 1)
        s131 [i] <= s131 [i - 1];
      s136 <= s251;
      s135 <= s136;
      s138 <= s252;
      s137 <= s138;
      s139 <= s300;
      s141 <= s174;
      s140 <= s141;
      s142 <= s105;
      s146 <= s284;
      s145 <= s146;
      s148 <= s285;
      s147 <= s148;
      s151 <= s329;
      s154 [0] <= s208;
      for (i = 1; i < 3; i = i + 1)
        s154 [i] <= s154 [i - 1];
      s156 <= s78;
      s157 <= s383;
      s158 <= s301;
      s160 <= s305;
      s159 <= s160;
      s161 <= s347;
      s163 <= s417;
      s164 <= s1;
      s166 <= s33;
      s167 <= s238;
      s169 [0] <= s16;
      for (i = 1; i < 4; i = i + 1)
        s169 [i] <= s169 [i - 1];
      s171 [0] <= s17;
      for (i = 1; i < 4; i = i + 1)
        s171 [i] <= s171 [i - 1];
      s172 <= s143;
      s173 <= s144;
      s174 <= s48;
      s176 [0] <= s298;
      for (i = 1; i < 49; i = i + 1)
        s176 [i] <= s176 [i - 1];
      s178 <= s87;
      s177 <= s178;
      s180 <= s275;
      s183 <= s381;
      s185 <= s30;
      s186 <= s31;
      s189 <= s133;
      s193 [0] <= s408;
      for (i = 1; i < 12; i = i + 1)
        s193 [i] <= s193 [i - 1];
      s195 [s225] <= s286;
      s194 <= s195 [s249];
      s199 <= s324;
      s200 <= s188;
      s201 <= s282;
      s202 <= s42;
      s205 <= s13;
      s204 <= s205;
      s207 [0] <= s192;
      for (i = 1; i < 8; i = i + 1)
        s207 [i] <= s207 [i - 1];
      s211 <= s234;
      s213 [0] <= s279;
      for (i = 1; i < 3; i = i + 1)
        s213 [i] <= s213 [i - 1];
      s214 <= s36;
      s215 <= s37;
      s217 <= s12;
      s216 <= s217;
      s219 [0] <= s117;
      for (i = 1; i < 29; i = i + 1)
        s219 [i] <= s219 [i - 1];
      s222 [0] <= s187;
      for (i = 1; i < 3; i = i + 1)
        s222 [i] <= s222 [i - 1];
      s223 <= s461;
      s226 <= s249;
      s225 <= s226;
      s227 <= s73;
      s230 [0] <= s380;
      for (i = 1; i < 3; i = i + 1)
        s230 [i] <= s230 [i - 1];
      s233 <= s191;
      s234 <= s232;
      s235 <= s79;
      s237 [0] <= s371;
      for (i = 1; i < 3; i = i + 1)
        s237 [i] <= s237 [i - 1];
      s239 <= s57;
      s243 <= s8;
      s244 <= s9;
      s245 <= s382;
      s250 [0] <= s342;
      for (i = 1; i < 29; i = i + 1)
        s250 [i] <= s250 [i - 1];
      s251 <= s426;
      s252 <= s427;
      s253 <= s200;
      s259 <= s263;
      s258 <= s259;
      s260 <= s273;
      s262 [0] <= s337;
      for (i = 1; i < 4; i = i + 1)
        s262 [i] <= s262 [i - 1];
      s264 <= s455;
      s265 <= s456;
      s267 <= s114;
      s270 <= s233;
      s269 <= s270;
      s272 <= s66;
      s271 <= s272;
      s274 <= s209;
      s276 <= s181;
      s277 <= s70;
      s281 <= s368;
      s280 <= s281;
      s294 [s111] <= s35;
      s293 <= s294 [s239];
      s295 <= s412;
      s296 <= s107;
      s299 [0] <= s130;
      for (i = 1; i < 8; i = i + 1)
        s299 [i] <= s299 [i - 1];
      s304 [0] <= s91;
      for (i = 1; i < 3; i = i + 1)
        s304 [i] <= s304 [i - 1];
      s305 <= s115;
      s308 <= s454;
      s309 <= s289;
      s310 <= s290;
      s313 [0] <= s419;
      for (i = 1; i < 31; i = i + 1)
        s313 [i] <= s313 [i - 1];
      s317 <= s41;
      s318 <= s113;
      s321 [0] <= s403;
      for (i = 1; i < 29; i = i + 1)
        s321 [i] <= s321 [i - 1];
      s330 <= s224;
      s332 <= s49;
      s336 <= s18;
      s335 <= s336;
      s338 [0] <= s206;
      for (i = 1; i < 14; i = i + 1)
        s338 [i] <= s338 [i - 1];
      s340 [s159] <= s288;
      s339 <= s340 [s305];
      s343 <= s308;
      s342 <= s343;
      s344 <= s63;
      s345 <= s64;
      s346 <= s104;
      s349 <= s108;
      s348 <= s349;
      s354 <= s228;
      s357 <= s369;
      s358 <= s302;
      s361 [0] <= s306;
      for (i = 1; i < 20; i = i + 1)
        s361 [i] <= s361 [i - 1];
      s363 [0] <= s307;
      for (i = 1; i < 20; i = i + 1)
        s363 [i] <= s363 [i - 1];
      s366 [0] <= s245;
      for (i = 1; i < 4; i = i + 1)
        s366 [i] <= s366 [i - 1];
      s367 <= s58;
      s373 <= s335;
      s375 [0] <= s446;
      for (i = 1; i < 4; i = i + 1)
        s375 [i] <= s375 [i - 1];
      s377 [0] <= s447;
      for (i = 1; i < 4; i = i + 1)
        s377 [i] <= s377 [i - 1];
      s384 <= s268;
      s386 <= s23;
      s385 <= s386;
      s387 <= s266;
      s388 <= s155;
      s389 <= s85;
      s391 <= s124;
      s393 [0] <= s235;
      for (i = 1; i < 4; i = i + 1)
        s393 [i] <= s393 [i - 1];
      s397 [s177] <= s352;
      s396 <= s397 [s87];
      s400 <= s80;
      s401 <= s152;
      s404 <= s218;
      s403 <= s404;
      s405 <= s370;
      s407 [0] <= s203;
      for (i = 1; i < 3; i = i + 1)
        s407 [i] <= s407 [i - 1];
      s408 <= s162;
      s411 [0] <= s29;
      for (i = 1; i < 3; i = i + 1)
        s411 [i] <= s411 [i - 1];
      s413 [s443] <= s179;
      s412 <= s413 [s405];
      s414 <= s449;
      s415 <= s409;
      s416 <= s90;
      s420 [0] <= s297;
      for (i = 1; i < 11; i = i + 1)
        s420 [i] <= s420 [i - 1];
      s424 <= s378;
      s425 <= s379;
      s428 <= s77;
      s430 [0] <= s432;
      for (i = 1; i < 31; i = i + 1)
        s430 [i] <= s430 [i - 1];
      s433 [0] <= s201;
      for (i = 1; i < 4; i = i + 1)
        s433 [i] <= s433 [i - 1];
      s435 <= s372;
      s436 <= s246;
      s437 <= s247;
      s438 <= s61;
      s439 <= s62;
      s441 <= s325;
      s444 <= s405;
      s443 <= s444;
      s448 <= s194;
      s450 [0] <= s240;
      for (i = 1; i < 3; i = i + 1)
        s450 [i] <= s450 [i - 1];
      s452 [0] <= s373;
      for (i = 1; i < 4; i = i + 1)
        s452 [i] <= s452 [i - 1];
      s453 <= s292;
      s455 <= s92;
      s456 <= s93;
      s458 [0] <= s156;
      for (i = 1; i < 20; i = i + 1)
        s458 [i] <= s458 [i - 1];
      s460 <= s76;
      s459 <= s460;
      s461 <= s184;
      s462 <= s328;
      s463 <= s356;
      s465 <= s319;
      s464 <= s465;
      s466 <= s293;
      s468 [0] <= s389;
      for (i = 1; i < 4; i = i + 1)
        s468 [i] <= s468 [i - 1];
      s469 <= s364;
    end
endmodule
