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
 * It has a latency of 66 cycles: the output will begin 66 cycles after the input has begun.
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
  reg [36:0] s13;
  wire [81:0] s14;
  reg [40:0] s15;
  reg [81:0] s16;
  reg [40:0] s17;
  reg [40:0] s18;
  reg s20 [13:0];
  wire s19;
  reg [36:0] s21;
  reg [40:0] s23 [2:0];
  wire [40:0] s22;
  reg [2:0] s24;
  reg s25;
  reg [36:0] s26;
  wire [36:0] s27;
  reg [40:0] s29;
  reg [40:0] s28;
  reg [40:0] s31;
  reg [40:0] s30;
  wire [40:0] s32;
  wire [40:0] s33;
  wire [40:0] s34;
  reg [40:0] s35;
  wire [40:0] s36;
  reg [40:0] s37;
  wire [81:0] s38;
  wire [40:0] s39;
  wire [40:0] s40;
  reg [40:0] s42;
  reg [40:0] s41;
  reg [40:0] s43;
  wire [36:0] s44;
  reg [81:0] s46 [7:0]; // synthesis attribute ram_style of s46 is block
  reg [81:0] s45;
  reg [2:0] s47;
  wire [40:0] s48;
  wire [40:0] s49;
  wire [40:0] s50;
  wire [40:0] s51;
  wire [40:0] s52;
  wire [40:0] s53;
  reg s55 [18:0];
  wire s54;
  reg [40:0] s56;
  wire s57;
  wire [1:0] s58;
  wire [77:0] s59;
  reg [2:0] s60;
  wire [40:0] s61;
  wire [40:0] s62;
  wire [40:0] s63;
  reg [40:0] s64;
  reg [40:0] s65;
  wire [40:0] s66;
  wire [40:0] s67;
  reg [40:0] s68;
  reg [40:0] s69;
  reg [40:0] s70;
  reg [40:0] s71;
  reg [40:0] s72;
  wire [40:0] s73;
  reg [36:0] s74;
  wire [40:0] s75;
  wire [36:0] s76;
  reg [81:0] s77;
  reg [40:0] s78;
  reg [40:0] s79;
  wire [36:0] s80;
  reg [36:0] s81;
  wire [2:0] s82;
  wire [40:0] s83;
  wire [40:0] s84;
  wire [40:0] s85;
  wire [40:0] s86;
  reg [36:0] s88 [30:0];
  wire [36:0] s87;
  reg s90;
  reg s89;
  reg [2:0] s92 [28:0];
  wire [2:0] s91;
  reg [36:0] s93;
  wire [40:0] s94;
  wire [40:0] s95;
  wire [40:0] s96;
  wire [40:0] s97;
  wire [40:0] s98;
  wire [2:0] s99;
  reg [40:0] s101 [3:0];
  wire [40:0] s100;
  reg [40:0] s103 [3:0];
  wire [40:0] s102;
  reg [40:0] s105;
  reg [40:0] s104;
  reg [40:0] s107;
  reg [40:0] s106;
  reg [40:0] s108;
  wire [40:0] s109;
  wire [40:0] s110;
  reg [40:0] s111;
  wire [81:0] s112;
  reg [40:0] s113;
  reg [40:0] s115 [3:0];
  wire [40:0] s114;
  reg [2:0] s117;
  reg [2:0] s116;
  wire [36:0] s118;
  reg [36:0] s119;
  reg [2:0] s120;
  reg s122 [2:0];
  wire s121;
  reg [81:0] s123;
  reg [36:0] s124;
  reg [40:0] s125;
  reg [40:0] s126;
  reg [40:0] s128 [2:0];
  wire [40:0] s127;
  reg [40:0] s130 [2:0];
  wire [40:0] s129;
  wire [40:0] s131;
  wire [40:0] s132;
  reg s134;
  reg s133;
  reg [40:0] s135;
  reg [40:0] s136;
  reg s138 [7:0];
  wire s137;
  wire [77:0] s139;
  wire [40:0] s140;
  reg [36:0] s141;
  wire [40:0] s142;
  wire [77:0] s143;
  wire [40:0] s144;
  reg [40:0] s146;
  reg [40:0] s145;
  reg [40:0] s148;
  reg [40:0] s147;
  reg [40:0] s149;
  wire [81:0] s150;
  reg [40:0] s152;
  reg [40:0] s151;
  reg [40:0] s153;
  reg [40:0] s154;
  reg [36:0] s155;
  reg [40:0] s156;
  wire [40:0] s157;
  wire [40:0] s158;
  wire [40:0] s159;
  reg [40:0] s161;
  reg [40:0] s160;
  reg [40:0] s163;
  reg [40:0] s162;
  wire [40:0] s164;
  wire [40:0] s165;
  reg [40:0] s167 [2:0];
  wire [40:0] s166;
  reg [40:0] s168;
  wire [40:0] s169;
  reg [40:0] s171 [2:0];
  wire [40:0] s170;
  reg [40:0] s172;
  reg [2:0] s173;
  wire [77:0] s174;
  reg [36:0] s175;
  reg [1:0] s176;
  reg [2:0] s178;
  reg [2:0] s177;
  reg [40:0] s179;
  wire [2:0] s180;
  reg [40:0] s181;
  reg [40:0] s182;
  wire [77:0] s183;
  wire [77:0] s184;
  reg [40:0] s185;
  reg [2:0] s186;
  reg [36:0] s187;
  reg [40:0] s189 [3:0];
  wire [40:0] s188;
  reg [40:0] s191 [3:0];
  wire [40:0] s190;
  reg [40:0] s192;
  reg [40:0] s193;
  reg [40:0] s194;
  wire [77:0] s195;
  reg [2:0] s197;
  reg [2:0] s196;
  wire [81:0] s198;
  wire [40:0] s199;
  reg [36:0] s200;
  wire [40:0] s201;
  reg [2:0] s202;
  reg [40:0] s203;
  wire [36:0] s204;
  wire [40:0] s205;
  reg [40:0] s206;
  reg [40:0] s207;
  wire [40:0] s208;
  reg [36:0] s209;
  reg [40:0] s210;
  wire [81:0] s211;
  wire [40:0] s212;
  reg [2:0] s214 [11:0];
  wire [2:0] s213;
  reg [81:0] s216 [7:0]; // synthesis attribute ram_style of s216 is block
  reg [81:0] s215;
  wire [77:0] s217;
  wire [40:0] s218;
  wire [40:0] s219;
  reg [40:0] s220;
  reg [36:0] s221;
  reg s222;
  reg [81:0] s223;
  wire [40:0] s224;
  reg [40:0] s226;
  reg [40:0] s225;
  reg [2:0] s228 [7:0];
  wire [2:0] s227;
  wire [40:0] s229;
  reg [40:0] s231 [2:0];
  wire [40:0] s230;
  wire [36:0] s232;
  wire [40:0] s233;
  reg [40:0] s234;
  reg [40:0] s236 [2:0];
  wire [40:0] s235;
  reg [40:0] s237;
  reg [40:0] s238;
  reg [40:0] s240;
  reg [40:0] s239;
  reg s242 [28:0];
  wire s241;
  wire [36:0] s243;
  reg [40:0] s244;
  reg [40:0] s245;
  reg [40:0] s247 [2:0];
  wire [40:0] s246;
  reg [40:0] s248;
  reg [36:0] s249;
  reg [2:0] s251;
  reg [2:0] s250;
  reg [36:0] s252;
  wire [40:0] s253;
  reg [40:0] s255 [2:0];
  wire [40:0] s254;
  wire [77:0] s256;
  wire [40:0] s257;
  wire [40:0] s258;
  reg [40:0] s259;
  reg [40:0] s260;
  reg [2:0] s262 [24:0];
  wire [2:0] s261;
  wire [77:0] s263;
  reg [40:0] s264;
  reg [40:0] s266 [2:0];
  wire [40:0] s265;
  reg [2:0] s267;
  reg [2:0] s268;
  wire [40:0] s269;
  wire [40:0] s270;
  wire [40:0] s271;
  wire [36:0] s272;
  reg [40:0] s273;
  reg [40:0] s274;
  reg [40:0] s275;
  wire [40:0] s276;
  wire [40:0] s277;
  wire [40:0] s278;
  reg [2:0] s280 [28:0];
  wire [2:0] s279;
  reg [40:0] s281;
  reg [40:0] s282;
  reg [36:0] s283;
  wire s284;
  wire s285;
  wire [1:0] s286;
  wire [1:0] s287;
  reg [81:0] s289;
  reg [81:0] s288;
  reg [40:0] s290;
  reg [36:0] s291;
  reg [2:0] s293 [3:0];
  wire [2:0] s292;
  wire [81:0] s294;
  reg [40:0] s295;
  reg [40:0] s296;
  wire [40:0] s297;
  reg [36:0] s298;
  wire [40:0] s299;
  reg [40:0] s301;
  reg [40:0] s300;
  reg [40:0] s303;
  reg [40:0] s302;
  wire [40:0] s304;
  reg [36:0] s305;
  reg [36:0] s306;
  reg [40:0] s307;
  reg [40:0] s308;
  wire [40:0] s309;
  wire [40:0] s310;
  reg [81:0] s312;
  reg [81:0] s311;
  wire s313;
  wire [40:0] s314;
  wire [40:0] s315;
  wire [40:0] s316;
  wire [81:0] s317;
  wire [81:0] s318;
  wire [81:0] s319;
  wire [40:0] s320;
  wire [40:0] s321;
  wire [40:0] s322;
  wire [40:0] s323;
  reg [81:0] s325 [7:0]; // synthesis attribute ram_style of s325 is block
  reg [81:0] s324;
  reg [81:0] s326;
  reg [81:0] s327;
  reg [36:0] s328;
  wire s329;
  reg s331 [7:0];
  wire s330;
  wire [40:0] s332;
  wire [1:0] s333;
  wire [40:0] s334;
  reg [40:0] s336 [2:0];
  wire [40:0] s335;
  wire [40:0] s337;
  reg [2:0] s338;
  wire [2:0] s339;
  wire [2:0] s340;
  reg [2:0] s341;
  wire [77:0] s342;
  reg [40:0] s343;
  reg [40:0] s344;
  wire [2:0] s345;
  reg [36:0] s346;
  reg s348 [30:0];
  wire s347;
  reg [40:0] s349;
  reg [2:0] s350;
  reg [40:0] s351;
  wire [40:0] s352;
  wire [40:0] s353;
  wire [40:0] s354;
  reg [36:0] s355;
  reg [36:0] s356;
  wire [81:0] s357;
  reg s359 [28:0];
  wire s358;
  wire [40:0] s360;
  wire [40:0] s361;
  wire [40:0] s362;
  reg [36:0] s363;
  wire [40:0] s364;
  reg [40:0] s365;
  wire [40:0] s366;
  wire [40:0] s367;
  wire [81:0] s368;
  wire [40:0] s369;
  reg [36:0] s370;
  wire s371;
  reg [40:0] s372;
  wire [40:0] s373;
  wire [40:0] s374;
  reg s376;
  reg s375;
  reg [2:0] s378 [13:0];
  wire [2:0] s377;
  reg [81:0] s380 [7:0]; // synthesis attribute ram_style of s380 is block
  reg [81:0] s379;
  wire [77:0] s381;
  reg [2:0] s383;
  reg [2:0] s382;
  reg [40:0] s384;
  reg [40:0] s385;
  wire [40:0] s386;
  reg [36:0] s387;
  wire [77:0] s388;
  reg [40:0] s389;
  wire [40:0] s390;
  reg [40:0] s392;
  reg [40:0] s391;
  reg [36:0] s393;
  wire [40:0] s394;
  wire [40:0] s395;
  wire [81:0] s396;
  wire [36:0] s397;
  wire [40:0] s398;
  reg [40:0] s399;
  wire [1:0] s400;
  wire [40:0] s401;
  wire [40:0] s402;
  reg [40:0] s403;
  reg [40:0] s404;
  wire s405;
  reg [2:0] s407 [19:0];
  wire [2:0] s406;
  reg [2:0] s409 [19:0];
  wire [2:0] s408;
  wire [36:0] s410;
  reg [40:0] s412 [3:0];
  wire [40:0] s411;
  reg [40:0] s413;
  reg [40:0] s415 [2:0];
  wire [40:0] s414;
  wire [81:0] s416;
  wire [40:0] s417;
  wire [2:0] s418;
  wire [40:0] s419;
  wire [40:0] s420;
  reg s421;
  reg [40:0] s423 [3:0];
  wire [40:0] s422;
  reg [40:0] s425 [3:0];
  wire [40:0] s424;
  wire [40:0] s426;
  wire [40:0] s427;
  wire [40:0] s428;
  wire [40:0] s429;
  wire [40:0] s430;
  wire [36:0] s431;
  reg s433 [53:0];
  wire s432;
  reg [40:0] s434;
  reg s436;
  reg s435;
  reg [40:0] s437;
  reg [40:0] s438;
  reg s439;
  wire [36:0] s440;
  reg [40:0] s441;
  reg [40:0] s443 [2:0];
  wire [40:0] s442;
  reg [40:0] s445 [3:0];
  wire [40:0] s444;
  wire [2:0] s446;
  wire [2:0] s447;
  reg [81:0] s449 [7:0]; // synthesis attribute ram_style of s449 is block
  reg [81:0] s448;
  wire [40:0] s450;
  wire [40:0] s451;
  reg [40:0] s452;
  reg [40:0] s453;
  wire [77:0] s454;
  reg s456;
  reg s455;
  reg [2:0] s457;
  reg [40:0] s459 [2:0];
  wire [40:0] s458;
  reg [2:0] s460;
  wire [40:0] s461;
  reg [40:0] s463 [2:0];
  wire [40:0] s462;
  reg [81:0] s465 [7:0]; // synthesis attribute ram_style of s465 is block
  reg [81:0] s464;
  reg [40:0] s466;
  reg [40:0] s467;
  reg [40:0] s468;
  wire [40:0] s469;
  wire [40:0] s470;
  reg s472 [10:0];
  wire s471;
  wire [40:0] s473;
  wire [40:0] s474;
  wire [40:0] s475;
  reg [40:0] s476;
  reg [40:0] s477;
  wire [40:0] s478;
  wire [40:0] s479;
  wire [40:0] s480;
  reg [36:0] s481;
  reg s483 [30:0];
  wire s482;
  wire [77:0] s484;
  reg s486 [3:0];
  wire s485;
  wire [40:0] s487;
  reg [40:0] s488;
  wire [40:0] s489;
  reg [40:0] s490;
  reg [40:0] s492 [2:0];
  wire [40:0] s491;
  reg [40:0] s493;
  reg [40:0] s494;
  reg [40:0] s495;
  reg [40:0] s496;
  wire [77:0] s497;
  reg [40:0] s498;
  wire [1:0] s499;
  reg [2:0] s501;
  reg [2:0] s500;
  wire [40:0] s502;
  reg [40:0] s504 [2:0];
  wire [40:0] s503;
  wire [40:0] s505;
  wire [40:0] s506;
  reg [36:0] s507;
  reg [81:0] s508;
  reg [40:0] s510 [2:0];
  wire [40:0] s509;
  reg s512 [3:0];
  wire s511;
  reg [40:0] s513;
  wire [2:0] s514;
  reg [40:0] s515;
  reg [40:0] s516;
  reg [2:0] s518 [19:0];
  wire [2:0] s517;
  reg [40:0] s520;
  reg [40:0] s519;
  reg [40:0] s521;
  reg [81:0] s522;
  reg [40:0] s523;
  reg [81:0] s525;
  reg [81:0] s524;
  reg [81:0] s526;
  reg s528 [3:0];
  wire s527;
  reg [36:0] s529;
  integer i;
  assign s1 = s25 ? s179 : s374;
  assign s2 = s3 [30];
  assign s4 = s305 + s356;
  assign s5 = s6 [19];
  assign s8 = s241 ? s218 : s315;
  assign s9 = s241 ? s219 : s316;
  assign s10 = s527 ? s100 : s444;
  assign s11 = s248 - s234;
  assign s14 = {s244, s245};
  assign s19 = s20 [13];
  assign s22 = s23 [2];
  assign s27 = s291 + s155;
  assign s32 = s59[75:35];
  assign s33 = s121 ? s394 : s164;
  assign s34 = s121 ? s395 : s165;
  assign s36 = s25 ? s168 : s373;
  assign s38 = {s290, s7};
  assign s39 = s329 ? s352 : s473;
  assign s40 = s329 ? s353 : s474;
  assign s44 = s221 - s200;
  assign s48 = s77[81:41];
  assign s49 = s77[40:0];
  assign s50 = s43 + s441;
  assign s51 = s35 + s182;
  assign s52 = s498 - s185;
  assign s53 = s22 - s438;
  assign s54 = s55 [18];
  assign s57 = s176 == 2'd2;
  assign s58 = s137 ? s400 : s176;
  assign s59 = $signed(s516) * $signed(s74);
  assign s61 = s54 ? s69 : s259;
  assign s62 = s375 ? s145 : s28;
  assign s63 = s375 ? s147 : s30;
  assign s66 = s19 ? s493 : s281;
  assign s67 = s19 ? s494 : s282;
  assign s73 = s254 - s513;
  assign s75 = s35 - s182;
  assign s76 = s221 + s200;
  assign s80 = s305 - s356;
  assign s82 = reset ? 3'd0 : s99;
  assign s83 = s89 ? s41 : s225;
  assign s84 = s435 ? s15 : s372;
  assign s85 = s358 ? s48 : s366;
  assign s86 = s358 ? s49 : s367;
  assign s87 = s88 [30];
  assign s91 = s92 [28];
  assign s94 = s43 - s441;
  assign s95 = s166 + s230;
  assign s96 = s484[75:35];
  assign s97 = s421 ? s18 : s506;
  assign s98 = s421 ? s17 : s505;
  assign s99 = s137 ? s345 : s173;
  assign s100 = s101 [3];
  assign s102 = s103 [3];
  assign s109 = s485 ? 41'd0 : s181;
  assign s110 = s343 + s434;
  assign s112 = s375 ? s524 : s357;
  assign s114 = s115 [3];
  assign s118 = s222 ? 37'd34359738368 : 37'd0;
  assign s121 = s122 [2];
  assign s127 = s128 [2];
  assign s129 = s130 [2];
  assign s131 = s458 + s513;
  assign s132 = s471 ? s220 : s399;
  assign s137 = s138 [7];
  assign s139 = $signed(s275) * $signed(37'd24296003999);
  assign s140 = s183[75:35];
  assign s142 = s78 - s308;
  assign s143 = $signed(s521) * $signed(s93);
  assign s144 = s388[75:35];
  assign s150 = {s349, s488};
  assign s157 = s358 ? s366 : s48;
  assign s158 = s358 ? s367 : s49;
  assign s159 = s135 - s136;
  assign o1 = s14;
  assign s164 = i0[40:0];
  assign s165 = i0[81:41];
  assign s166 = s167 [2];
  assign s169 = 41'd0 - s509;
  assign s170 = s171 [2];
  assign s174 = $signed(s153) * $signed(s507);
  assign s180 = next ? 3'd0 : s446;
  assign s183 = $signed(s365) * $signed(s393);
  assign s184 = $signed(s129) * $signed(s175);
  assign s188 = s189 [3];
  assign s190 = s191 [3];
  assign s195 = $signed(s351) * $signed(s187);
  assign s198 = {s71, s72};
  assign s199 = s174[75:35];
  assign s201 = s89 ? s519 : s151;
  assign s204 = s291 - s155;
  assign o0 = s150;
  assign s205 = s439 ? s307 : s271;
  assign s208 = s184[75:35];
  assign s211 = s133 ? s288 : s294;
  assign s212 = s170 - s235;
  assign s213 = s214 [11];
  assign s217 = $signed(s70) * $signed(s283);
  assign s218 = s223[40:0];
  assign s219 = s223[81:41];
  assign s224 = s256[75:35];
  assign s227 = s228 [7];
  assign s229 = s497[75:35];
  assign s230 = s231 [2];
  assign s232 = s222 ? 37'd0 : 37'd34359738368;
  assign s233 = s25 ? s374 : s179;
  assign s235 = s236 [2];
  assign s241 = s242 [28];
  assign s243 = s2 + s87;
  assign s246 = s247 [2];
  assign s253 = s344 + s490;
  assign s254 = s255 [2];
  assign s256 = $signed(s260) * $signed(s26);
  assign s257 = s439 ? s264 : s270;
  assign s258 = s503 - s230;
  assign s261 = s262 [24];
  assign s263 = $signed(s126) * $signed(s346);
  assign s265 = s266 [2];
  assign s269 = s139[75:35];
  assign s270 = s522[81:41];
  assign s271 = s522[40:0];
  assign s272 = s328 + s124;
  assign s276 = s237 + s476;
  assign s277 = s238 + s477;
  assign s278 = s527 ? s102 : s114;
  assign s279 = s280 [28];
  assign s284 = s460[2];
  assign s285 = s460[0];
  assign s286 = s460[2:1];
  assign s287 = s460[1:0];
  assign s292 = s293 [3];
  assign s294 = {s413, s404};
  assign s297 = s343 - s434;
  assign s299 = s265 - s389;
  assign s304 = s471 ? s437 : s156;
  assign s309 = s246 + s438;
  assign s310 = s217[75:35];
  assign s313 = s330 ? 1'd0 : s405;
  assign s314 = s347 ? s468 : s37;
  assign s315 = s526[40:0];
  assign s316 = s526[81:41];
  assign s317 = {s149, s523};
  assign s318 = {s467, s403};
  assign s319 = {s206, s207};
  assign s320 = s511 ? s422 : s188;
  assign s321 = s511 ? s424 : s190;
  assign s322 = s64 - s65;
  assign s323 = s482 ? 41'd0 : s108;
  assign s329 = s213[1];
  assign s330 = s331 [7];
  assign s332 = s347 ? s203 : s210;
  assign s333 = reset ? 2'd0 : s58;
  assign s334 = s54 ? s12 : s113;
  assign s335 = s336 [2];
  assign s337 = s195[75:35];
  assign s339 = {s285, s286};
  assign s340 = {s287, s284};
  assign s342 = $signed(s154) * $signed(s21);
  assign s345 = s371 ? 3'd0 : s447;
  assign s347 = s348 [30];
  assign s352 = s326[81:41];
  assign s353 = s326[40:0];
  assign s354 = s491 - s442;
  assign s357 = {s384, s385};
  assign s358 = s359 [28];
  assign s360 = s455 ? s160 : s104;
  assign s361 = s455 ? s162 : s106;
  assign s362 = s344 - s490;
  assign s364 = s25 ? s373 : s168;
  assign s366 = s508[81:41];
  assign s367 = s508[40:0];
  assign s368 = s89 ? s311 : s416;
  assign s369 = s133 ? s300 : s302;
  assign s371 = s173 == 3'd5;
  assign s373 = s16[81:41];
  assign s374 = s16[40:0];
  assign s377 = s378 [13];
  assign s381 = $signed(s127) * $signed(s529);
  assign s386 = s263[75:35];
  assign s388 = $signed(s125) * $signed(s13);
  assign s390 = s133 ? s391 : s239;
  assign s394 = i1[40:0];
  assign s395 = i1[81:41];
  assign s396 = {s56, s111};
  assign s397 = s328 - s124;
  assign s398 = s498 + s185;
  assign s400 = s57 ? 2'd0 : s499;
  assign s401 = s342[75:35];
  assign s402 = s347 ? s37 : s468;
  assign s405 = s222 + 1'd1;
  assign s406 = s407 [19];
  assign s408 = s409 [19];
  assign s410 = s370 - s298;
  assign s411 = s412 [3];
  assign s414 = s415 [2];
  assign s416 = {s452, s68};
  assign s417 = s471 ? s399 : s220;
  assign s418 = s186 ^ s120;
  assign s419 = s454[75:35];
  assign s420 = s462 + s389;
  assign s422 = s423 [3];
  assign s424 = s425 [3];
  assign s426 = s329 ? s473 : s352;
  assign s427 = s329 ? s474 : s353;
  assign s428 = s143[75:35];
  assign s429 = s78 + s308;
  assign s430 = s273 - s274;
  assign s431 = s370 + s298;
  assign s432 = s433 [53];
  assign s440 = s2 - s87;
  assign s442 = s443 [2];
  assign s444 = s445 [3];
  assign s446 = s460 + 3'd1;
  assign s447 = s173 + 3'd1;
  assign s450 = s121 ? s164 : s394;
  assign s451 = s121 ? s165 : s395;
  assign s454 = $signed(s515) * $signed(s481);
  assign s458 = s459 [2];
  assign s461 = s471 ? s156 : s437;
  assign next_out = s432;
  assign s462 = s463 [2];
  assign s469 = s295 - s296;
  assign s470 = s381[75:35];
  assign s471 = s472 [10];
  assign s473 = s123[81:41];
  assign s474 = s123[40:0];
  assign s475 = s435 ? s194 : s79;
  assign s478 = s237 - s476;
  assign s479 = s238 - s477;
  assign s480 = s192 - s193;
  assign s482 = s483 [30];
  assign s484 = $signed(s496) * $signed(s355);
  assign s485 = s486 [3];
  assign s487 = s347 ? s210 : s203;
  assign s489 = s414 + s442;
  assign s491 = s492 [2];
  assign s497 = $signed(s495) * $signed(s252);
  assign s499 = s176 + 2'd1;
  assign s502 = s335 + s235;
  assign s503 = s504 [2];
  assign s505 = s327[81:41];
  assign s506 = s327[40:0];
  assign s509 = s510 [2];
  assign s511 = s512 [3];
  assign s514 = s47 ^ s60;
  assign s517 = s518 [19];
  assign s527 = s528 [3];
  always @(*)
    case(s517)
      0: s24 = 3'd0;
      1: s24 = 3'd2;
      2: s24 = 3'd3;
      3: s24 = 3'd7;
      4: s24 = 3'd5;
      default: s24 = 3'd4;
    endcase
  always @(*)
    case(s261)
      0: s81 = 37'd34194286836;
      1: s81 = 37'd32880219569;
      2: s81 = 37'd30302583904;
      3: s81 = 37'd26560436933;
      4: s81 = 37'd21797587266;
      5: s81 = 37'd16197068544;
      6: s81 = 37'd9974105562;
      7: s81 = 37'd3367843297;
    endcase
  always @(*)
    case(s377)
      0: s119 = 37'd0;
      1: s119 = 37'd0;
      2: s119 = 37'd0;
      3: s119 = 37'd24296003999;
      4: s119 = 37'd0;
      5: s119 = 37'd34359738368;
      6: s119 = 37'd0;
      7: s119 = 37'd24296003999;
    endcase
  always @(*)
    case(s261)
      0: s141 = 37'd3367843297;
      1: s141 = 37'd9974105562;
      2: s141 = 37'd16197068544;
      3: s141 = 37'd21797587266;
      4: s141 = 37'd26560436933;
      5: s141 = 37'd30302583904;
      6: s141 = 37'd32880219569;
      7: s141 = 37'd34194286836;
    endcase
  always @(*)
    case(s292)
      0: s172 = s411;
      1: s172 = s411;
      2: s172 = s411;
      3: s172 = s466;
      4: s172 = s411;
      5: s172 = 41'd0;
      6: s172 = s411;
      7: s172 = s453;
    endcase
  always @(*)
    case(s5)
      0: s202 = s227;
      1: s202 = s406;
      default: s202 = s408;
    endcase
  always @(*)
    case(s377)
      0: s209 = 37'd34359738368;
      1: s209 = 37'd34359738368;
      2: s209 = 37'd31744259020;
      3: s209 = 37'd13148902613;
      4: s209 = 37'd24296003999;
      5: s209 = 37'd113142949473;
      6: s209 = 37'd13148902613;
      7: s209 = 37'd105694694452;
    endcase
  always @(*)
    case(s377)
      0: s249 = 37'd34359738368;
      1: s249 = 37'd34359738368;
      2: s249 = 37'd34359738368;
      3: s249 = 37'd24296003999;
      4: s249 = 37'd34359738368;
      5: s249 = 37'd0;
      6: s249 = 37'd34359738368;
      7: s249 = 37'd113142949473;
    endcase
  always @(*)
    case(s173)
      0: s267 = 3'd0;
      1: s267 = 3'd4;
      2: s267 = 3'd5;
      3: s267 = 3'd7;
      4: s267 = 3'd3;
      default: s267 = 3'd2;
    endcase
  always @(*)
    case(s377)
      0: s306 = 37'd0;
      1: s306 = 37'd0;
      2: s306 = 37'd124290050859;
      3: s306 = 37'd105694694452;
      4: s306 = 37'd113142949473;
      5: s306 = 37'd113142949473;
      6: s306 = 37'd105694694452;
      7: s306 = 37'd13148902613;
    endcase
  always @(*)
    case(s176)
      0: s350 = s460;
      1: s350 = s340;
      default: s350 = s339;
    endcase
  always @(*)
    case(s261)
      0: s363 = 37'd0;
      1: s363 = 37'd6703252422;
      2: s363 = 37'd13148902613;
      3: s363 = 37'd19089247851;
      4: s363 = 37'd24296003999;
      5: s363 = 37'd28569078339;
      6: s363 = 37'd31744259020;
      7: s363 = 37'd33699525629;
    endcase
  always @(*)
    case(s261)
      0: s387 = 37'd34359738368;
      1: s387 = 37'd33699525629;
      2: s387 = 37'd31744259020;
      3: s387 = 37'd28569078339;
      4: s387 = 37'd24296003999;
      5: s387 = 37'd19089247851;
      6: s387 = 37'd13148902613;
      7: s387 = 37'd6703252422;
    endcase
  always @(posedge clk)
    begin
      s3 [0] <= s305;
      for (i = 1; i < 31; i = i + 1)
        s3 [i] <= s3 [i - 1];
      s6 [0] <= s176;
      for (i = 1; i < 20; i = i + 1)
        s6 [i] <= s6 [i - 1];
      s7 <= s132;
      s12 <= s309;
      s13 <= s204;
      s15 <= s398;
      s16 <= s211;
      s17 <= s62;
      s18 <= s63;
      s20 [0] <= s285;
      for (i = 1; i < 14; i = i + 1)
        s20 [i] <= s20 [i - 1];
      s21 <= s272;
      s23 [0] <= s470;
      for (i = 1; i < 3; i = i + 1)
        s23 [i] <= s23 [i - 1];
      s25 <= s133;
      s26 <= s243;
      s29 <= s493;
      s28 <= s29;
      s31 <= s494;
      s30 <= s31;
      s35 <= s233;
      s37 <= s50;
      s42 <= s372;
      s41 <= s42;
      s43 <= s278;
      s46 [s382] <= s318;
      s45 <= s46 [s341];
      s47 <= s24;
      s55 [0] <= s511;
      for (i = 1; i < 19; i = i + 1)
        s55 [i] <= s55 [i - 1];
      s56 <= s487;
      s60 <= s202;
      s64 <= s360;
      s65 <= s361;
      s68 <= s475;
      s69 <= s53;
      s70 <= s322;
      s71 <= s450;
      s72 <= s451;
      s74 <= s4;
      s77 <= s448;
      s78 <= s10;
      s79 <= s75;
      s88 [0] <= s356;
      for (i = 1; i < 31; i = i + 1)
        s88 [i] <= s88 [i - 1];
      s90 <= s435;
      s89 <= s90;
      s92 [0] <= s116;
      for (i = 1; i < 29; i = i + 1)
        s92 [i] <= s92 [i - 1];
      s93 <= s440;
      s101 [0] <= s270;
      for (i = 1; i < 4; i = i + 1)
        s101 [i] <= s101 [i - 1];
      s103 [0] <= s271;
      for (i = 1; i < 4; i = i + 1)
        s103 [i] <= s103 [i - 1];
      s105 <= s218;
      s104 <= s105;
      s107 <= s219;
      s106 <= s107;
      s108 <= s11;
      s111 <= s314;
      s113 <= s502;
      s115 [0] <= s307;
      for (i = 1; i < 4; i = i + 1)
        s115 [i] <= s115 [i - 1];
      s117 <= s268;
      s116 <= s117;
      s120 <= s350;
      s122 [0] <= s284;
      for (i = 1; i < 3; i = i + 1)
        s122 [i] <= s122 [i - 1];
      s123 <= s379;
      s124 <= s363;
      s125 <= s135;
      s126 <= s136;
      s128 [0] <= s273;
      for (i = 1; i < 3; i = i + 1)
        s128 [i] <= s128 [i - 1];
      s130 [0] <= s274;
      for (i = 1; i < 3; i = i + 1)
        s130 [i] <= s130 [i - 1];
      s134 <= s54;
      s133 <= s134;
      s135 <= s85;
      s136 <= s86;
      s138 [0] <= next;
      for (i = 1; i < 8; i = i + 1)
        s138 [i] <= s138 [i - 1];
      s146 <= s281;
      s145 <= s146;
      s148 <= s282;
      s147 <= s148;
      s149 <= s332;
      s152 <= s194;
      s151 <= s152;
      s153 <= s192;
      s154 <= s193;
      s155 <= s141;
      s156 <= s110;
      s161 <= s315;
      s160 <= s161;
      s163 <= s316;
      s162 <= s163;
      s167 [0] <= s386;
      for (i = 1; i < 3; i = i + 1)
        s167 [i] <= s167 [i - 1];
      s168 <= s369;
      s171 [0] <= s229;
      for (i = 1; i < 3; i = i + 1)
        s171 [i] <= s171 [i - 1];
      s173 <= s82;
      s175 <= s431;
      s176 <= s333;
      s178 <= s338;
      s177 <= s178;
      s179 <= s390;
      s181 <= s469;
      s182 <= s1;
      s185 <= s36;
      s186 <= s267;
      s187 <= s328;
      s189 [0] <= s17;
      for (i = 1; i < 4; i = i + 1)
        s189 [i] <= s189 [i - 1];
      s191 [0] <= s18;
      for (i = 1; i < 4; i = i + 1)
        s191 [i] <= s191 [i - 1];
      s192 <= s157;
      s193 <= s158;
      s194 <= s51;
      s197 <= s91;
      s196 <= s197;
      s200 <= s306;
      s203 <= s429;
      s206 <= s33;
      s207 <= s34;
      s210 <= s142;
      s214 [0] <= s460;
      for (i = 1; i < 12; i = i + 1)
        s214 [i] <= s214 [i - 1];
      s216 [s250] <= s317;
      s215 <= s216 [s279];
      s220 <= s362;
      s221 <= s209;
      s222 <= s313;
      s223 <= s45;
      s226 <= s15;
      s225 <= s226;
      s228 [0] <= s213;
      for (i = 1; i < 8; i = i + 1)
        s228 [i] <= s228 [i - 1];
      s231 [0] <= s140;
      for (i = 1; i < 3; i = i + 1)
        s231 [i] <= s231 [i - 1];
      s234 <= s260;
      s236 [0] <= s310;
      for (i = 1; i < 3; i = i + 1)
        s236 [i] <= s236 [i - 1];
      s237 <= s39;
      s238 <= s40;
      s240 <= s12;
      s239 <= s240;
      s242 [0] <= s121;
      for (i = 1; i < 29; i = i + 1)
        s242 [i] <= s242 [i - 1];
      s244 <= s258;
      s245 <= s95;
      s247 [0] <= s208;
      for (i = 1; i < 3; i = i + 1)
        s247 [i] <= s247 [i - 1];
      s248 <= s521;
      s251 <= s279;
      s250 <= s251;
      s252 <= s76;
      s255 [0] <= s428;
      for (i = 1; i < 3; i = i + 1)
        s255 [i] <= s255 [i - 1];
      s259 <= s212;
      s260 <= s257;
      s262 [0] <= s292;
      for (i = 1; i < 25; i = i + 1)
        s262 [i] <= s262 [i - 1];
      s264 <= s83;
      s266 [0] <= s419;
      for (i = 1; i < 3; i = i + 1)
        s266 [i] <= s266 [i - 1];
      s268 <= s60;
      s273 <= s8;
      s274 <= s9;
      s275 <= s430;
      s280 [0] <= s382;
      for (i = 1; i < 29; i = i + 1)
        s280 [i] <= s280 [i - 1];
      s281 <= s478;
      s282 <= s479;
      s283 <= s221;
      s289 <= s294;
      s288 <= s289;
      s290 <= s304;
      s291 <= s81;
      s293 [0] <= s377;
      for (i = 1; i < 4; i = i + 1)
        s293 [i] <= s293 [i - 1];
      s295 <= s515;
      s296 <= s516;
      s298 <= s119;
      s301 <= s259;
      s300 <= s301;
      s303 <= s69;
      s302 <= s303;
      s305 <= s232;
      s307 <= s201;
      s308 <= s73;
      s312 <= s416;
      s311 <= s312;
      s325 [s116] <= s38;
      s324 <= s325 [s268];
      s326 <= s464;
      s327 <= s112;
      s328 <= s387;
      s331 [0] <= s137;
      for (i = 1; i < 8; i = i + 1)
        s331 [i] <= s331 [i - 1];
      s336 [0] <= s96;
      for (i = 1; i < 3; i = i + 1)
        s336 [i] <= s336 [i - 1];
      s338 <= s120;
      s341 <= s514;
      s343 <= s320;
      s344 <= s321;
      s346 <= s27;
      s348 [0] <= s471;
      for (i = 1; i < 31; i = i + 1)
        s348 [i] <= s348 [i - 1];
      s349 <= s354;
      s351 <= s480;
      s355 <= s44;
      s356 <= s118;
      s359 [0] <= s455;
      for (i = 1; i < 29; i = i + 1)
        s359 [i] <= s359 [i - 1];
      s365 <= s159;
      s370 <= s249;
      s372 <= s52;
      s376 <= s19;
      s375 <= s376;
      s378 [0] <= s227;
      for (i = 1; i < 14; i = i + 1)
        s378 [i] <= s378 [i - 1];
      s380 [s177] <= s319;
      s379 <= s380 [s338];
      s383 <= s341;
      s382 <= s383;
      s384 <= s66;
      s385 <= s67;
      s389 <= s109;
      s392 <= s113;
      s391 <= s392;
      s393 <= s291;
      s399 <= s253;
      s403 <= s417;
      s404 <= s334;
      s407 [0] <= s339;
      for (i = 1; i < 20; i = i + 1)
        s407 [i] <= s407 [i - 1];
      s409 [0] <= s340;
      for (i = 1; i < 20; i = i + 1)
        s409 [i] <= s409 [i - 1];
      s412 [0] <= s275;
      for (i = 1; i < 4; i = i + 1)
        s412 [i] <= s412 [i - 1];
      s413 <= s61;
      s415 [0] <= s401;
      for (i = 1; i < 3; i = i + 1)
        s415 [i] <= s415 [i - 1];
      s421 <= s375;
      s423 [0] <= s505;
      for (i = 1; i < 4; i = i + 1)
        s423 [i] <= s423 [i - 1];
      s425 [0] <= s506;
      for (i = 1; i < 4; i = i + 1)
        s425 [i] <= s425 [i - 1];
      s433 [0] <= s330;
      for (i = 1; i < 54; i = i + 1)
        s433 [i] <= s433 [i - 1];
      s434 <= s299;
      s436 <= s25;
      s435 <= s436;
      s437 <= s297;
      s438 <= s172;
      s439 <= s89;
      s441 <= s131;
      s443 [0] <= s337;
      for (i = 1; i < 3; i = i + 1)
        s443 [i] <= s443 [i - 1];
      s445 [0] <= s264;
      for (i = 1; i < 4; i = i + 1)
        s445 [i] <= s445 [i - 1];
      s449 [s196] <= s396;
      s448 <= s449 [s91];
      s452 <= s84;
      s453 <= s169;
      s456 <= s241;
      s455 <= s456;
      s457 <= s418;
      s459 [0] <= s224;
      for (i = 1; i < 3; i = i + 1)
        s459 [i] <= s459 [i - 1];
      s460 <= s180;
      s463 [0] <= s32;
      for (i = 1; i < 3; i = i + 1)
        s463 [i] <= s463 [i - 1];
      s465 [s500] <= s198;
      s464 <= s465 [s457];
      s466 <= s509;
      s467 <= s461;
      s468 <= s94;
      s472 [0] <= s329;
      for (i = 1; i < 11; i = i + 1)
        s472 [i] <= s472 [i - 1];
      s476 <= s426;
      s477 <= s427;
      s481 <= s80;
      s483 [0] <= s485;
      for (i = 1; i < 31; i = i + 1)
        s483 [i] <= s483 [i - 1];
      s486 [0] <= s222;
      for (i = 1; i < 4; i = i + 1)
        s486 [i] <= s486 [i - 1];
      s488 <= s489;
      s490 <= s420;
      s492 [0] <= s199;
      for (i = 1; i < 3; i = i + 1)
        s492 [i] <= s492 [i - 1];
      s493 <= s276;
      s494 <= s277;
      s495 <= s64;
      s496 <= s65;
      s498 <= s364;
      s501 <= s457;
      s500 <= s501;
      s504 [0] <= s144;
      for (i = 1; i < 3; i = i + 1)
        s504 [i] <= s504 [i - 1];
      s507 <= s397;
      s508 <= s215;
      s510 [0] <= s269;
      for (i = 1; i < 3; i = i + 1)
        s510 [i] <= s510 [i - 1];
      s512 [0] <= s421;
      for (i = 1; i < 4; i = i + 1)
        s512 [i] <= s512 [i - 1];
      s513 <= s323;
      s515 <= s97;
      s516 <= s98;
      s518 [0] <= s173;
      for (i = 1; i < 20; i = i + 1)
        s518 [i] <= s518 [i - 1];
      s520 <= s79;
      s519 <= s520;
      s521 <= s205;
      s522 <= s368;
      s523 <= s402;
      s525 <= s357;
      s524 <= s525;
      s526 <= s324;
      s528 [0] <= s439;
      for (i = 1; i < 4; i = i + 1)
        s528 [i] <= s528 [i - 1];
      s529 <= s410;
    end
endmodule
