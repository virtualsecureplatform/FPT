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
 * It has a latency of 47 cycles: the output will begin 47 cycles after the input has begun.
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

  reg [37:0] s1;
  reg [75:0] s3 [3:0]; // synthesis attribute ram_style of s3 is block
  reg [75:0] s2;
  reg [75:0] s5 [3:0]; // synthesis attribute ram_style of s5 is block
  reg [75:0] s4;
  reg [75:0] s6;
  reg [37:0] s7;
  wire [37:0] s8;
  reg [37:0] s9;
  reg [37:0] s10;
  wire [37:0] s11;
  reg [75:0] s13 [3:0]; // synthesis attribute ram_style of s13 is block
  reg [75:0] s12;
  reg [33:0] s14;
  reg [33:0] s15;
  wire [37:0] s16;
  wire [33:0] s17;
  reg [33:0] s18;
  wire s19;
  reg [37:0] s20;
  reg [37:0] s21;
  reg [37:0] s22;
  reg [37:0] s24 [2:0];
  wire [37:0] s23;
  wire [75:0] s25;
  reg [33:0] s26;
  wire [37:0] s27;
  reg s29 [37:0];
  wire s28;
  reg [33:0] s30;
  reg [1:0] s31;
  reg [37:0] s32;
  reg [75:0] s34 [3:0]; // synthesis attribute ram_style of s34 is block
  reg [75:0] s33;
  wire [37:0] s35;
  wire [75:0] s36;
  wire [37:0] s37;
  wire [37:0] s38;
  reg [37:0] s39;
  wire [37:0] s40;
  wire [37:0] s41;
  wire [37:0] s42;
  wire [71:0] s43;
  reg [37:0] s44;
  reg [37:0] s45;
  reg [37:0] s46;
  reg [37:0] s47;
  wire [37:0] s48;
  reg [37:0] s49;
  reg [37:0] s50;
  reg [37:0] s51;
  reg [37:0] s52;
  reg [37:0] s54 [2:0];
  wire [37:0] s53;
  wire [37:0] s55;
  wire [37:0] s56;
  reg [1:0] s58 [18:0];
  wire [1:0] s57;
  reg [37:0] s59;
  wire [37:0] s60;
  wire [37:0] s61;
  reg [33:0] s62;
  reg [37:0] s63;
  reg [1:0] s65 [18:0];
  wire [1:0] s64;
  reg [33:0] s66;
  wire [37:0] s67;
  wire [37:0] s68;
  reg [37:0] s69;
  reg [37:0] s70;
  reg s72 [3:0];
  wire s71;
  reg [33:0] s73;
  reg [37:0] s75 [2:0];
  wire [37:0] s74;
  wire [71:0] s76;
  reg [33:0] s77;
  wire [71:0] s78;
  reg [37:0] s79;
  reg [33:0] s80;
  wire s81;
  wire s82;
  reg s84 [9:0];
  wire s83;
  wire [37:0] s85;
  wire [37:0] s86;
  wire [37:0] s87;
  wire [37:0] s88;
  reg [37:0] s89;
  wire [37:0] s90;
  wire [37:0] s91;
  wire [37:0] s92;
  wire [37:0] s93;
  wire [37:0] s94;
  wire [37:0] s95;
  reg [37:0] s96;
  reg [33:0] s97;
  wire [37:0] s98;
  wire [37:0] s99;
  wire [33:0] s100;
  reg s102 [8:0];
  wire s101;
  wire [37:0] s103;
  wire [37:0] s104;
  reg [1:0] s105;
  reg [75:0] s106;
  reg [37:0] s108 [2:0];
  wire [37:0] s107;
  reg [37:0] s110 [2:0];
  wire [37:0] s109;
  reg s111;
  wire [37:0] s112;
  reg [33:0] s113;
  wire [37:0] s114;
  reg s116 [6:0];
  wire s115;
  reg [75:0] s117;
  wire [1:0] s118;
  wire [37:0] s119;
  wire [37:0] s120;
  wire [37:0] s121;
  reg [37:0] s122;
  reg [37:0] s123;
  reg [1:0] s125 [11:0];
  wire [1:0] s124;
  wire [37:0] s126;
  reg [1:0] s128 [3:0];
  wire [1:0] s127;
  wire [37:0] s129;
  wire [37:0] s130;
  reg [33:0] s131;
  reg [37:0] s132;
  reg [37:0] s133;
  wire [75:0] s134;
  wire [37:0] s135;
  wire [37:0] s136;
  wire [37:0] s137;
  reg [37:0] s138;
  reg [37:0] s139;
  reg [37:0] s141 [4:0];
  wire [37:0] s140;
  reg [37:0] s143 [4:0];
  wire [37:0] s142;
  reg [37:0] s144;
  reg [37:0] s145;
  wire [75:0] s146;
  reg [33:0] s147;
  reg [1:0] s149 [22:0];
  wire [1:0] s148;
  wire [37:0] s150;
  reg [37:0] s151;
  reg [37:0] s152;
  wire [37:0] s153;
  wire [37:0] s154;
  wire [71:0] s155;
  reg [37:0] s156;
  reg [37:0] s157;
  wire [33:0] s158;
  reg [75:0] s160 [3:0]; // synthesis attribute ram_style of s160 is block
  reg [75:0] s159;
  wire [37:0] s161;
  reg [1:0] s163 [18:0];
  wire [1:0] s162;
  reg [37:0] s164;
  reg [33:0] s165;
  wire [37:0] s166;
  wire [37:0] s167;
  reg [75:0] s168;
  reg [37:0] s169;
  reg [75:0] s170;
  reg [37:0] s171;
  reg [37:0] s172;
  reg [37:0] s173;
  wire [37:0] s174;
  reg [1:0] s176 [18:0];
  wire [1:0] s175;
  reg [75:0] s177;
  reg [37:0] s178;
  reg [37:0] s179;
  reg [37:0] s180;
  wire [71:0] s181;
  wire [37:0] s182;
  wire [37:0] s183;
  reg s185 [9:0];
  wire s184;
  wire [37:0] s186;
  reg [37:0] s187;
  reg [37:0] s188;
  reg [37:0] s190;
  reg [37:0] s189;
  reg [37:0] s192;
  reg [37:0] s191;
  reg [33:0] s193;
  wire [37:0] s194;
  reg [75:0] s195;
  reg [37:0] s196;
  reg [37:0] s197;
  wire [37:0] s198;
  reg [37:0] s199;
  reg [37:0] s200;
  wire [37:0] s201;
  wire [37:0] s202;
  wire [37:0] s203;
  reg [75:0] s205 [3:0]; // synthesis attribute ram_style of s205 is block
  reg [75:0] s204;
  reg [1:0] s206;
  reg [37:0] s208 [2:0];
  wire [37:0] s207;
  wire [37:0] s209;
  wire [37:0] s210;
  reg [75:0] s211;
  reg [33:0] s212;
  reg [33:0] s213;
  wire [37:0] s214;
  wire [1:0] s215;
  reg s217 [8:0];
  wire s216;
  wire [37:0] s218;
  wire [37:0] s219;
  wire [37:0] s220;
  reg [37:0] s221;
  reg [37:0] s222;
  reg [37:0] s223;
  reg [37:0] s224;
  reg [37:0] s225;
  reg [33:0] s226;
  reg [37:0] s227;
  reg [37:0] s228;
  reg [37:0] s229;
  reg [33:0] s230;
  reg [37:0] s232 [2:0];
  wire [37:0] s231;
  wire [37:0] s233;
  reg [37:0] s234;
  reg [33:0] s235;
  reg s237 [2:0];
  wire s236;
  reg [37:0] s238;
  wire [71:0] s239;
  wire [37:0] s240;
  wire [37:0] s241;
  wire [37:0] s242;
  reg [33:0] s243;
  reg [37:0] s244;
  reg [37:0] s245;
  wire [37:0] s246;
  reg [33:0] s247;
  wire [33:0] s248;
  wire [33:0] s249;
  reg [33:0] s250;
  wire [37:0] s251;
  wire [37:0] s252;
  reg [37:0] s253;
  reg [37:0] s255;
  reg [37:0] s254;
  reg [37:0] s257;
  reg [37:0] s256;
  wire [37:0] s258;
  wire [37:0] s259;
  reg [37:0] s260;
  wire [37:0] s261;
  reg [37:0] s262;
  reg [37:0] s264 [2:0];
  wire [37:0] s263;
  wire [37:0] s265;
  reg [37:0] s266;
  reg [37:0] s267;
  reg [75:0] s268;
  reg [37:0] s270 [2:0];
  wire [37:0] s269;
  reg [37:0] s271;
  wire [37:0] s272;
  wire [37:0] s273;
  reg [75:0] s274;
  reg [37:0] s275;
  reg [33:0] s276;
  wire [75:0] s277;
  wire [37:0] s278;
  reg [37:0] s279;
  reg [75:0] s280;
  wire [37:0] s281;
  reg [37:0] s282;
  reg [37:0] s283;
  wire [37:0] s284;
  wire [37:0] s285;
  reg [37:0] s286;
  wire [37:0] s287;
  reg [37:0] s288;
  reg [37:0] s289;
  wire [37:0] s290;
  wire [37:0] s291;
  reg [37:0] s292;
  reg [37:0] s293;
  reg [37:0] s294;
  reg [37:0] s295;
  wire [37:0] s296;
  reg [37:0] s297;
  wire [37:0] s298;
  reg [37:0] s299;
  reg [37:0] s300;
  reg [37:0] s302;
  reg [37:0] s301;
  reg [37:0] s304;
  reg [37:0] s303;
  wire [71:0] s305;
  wire [37:0] s306;
  wire [37:0] s307;
  reg [33:0] s309 [27:0];
  wire [33:0] s308;
  reg [37:0] s310;
  wire [37:0] s311;
  reg [33:0] s312;
  wire [37:0] s313;
  wire [37:0] s314;
  wire [75:0] s315;
  wire [37:0] s316;
  reg [37:0] s317;
  reg [37:0] s318;
  reg [33:0] s319;
  reg [1:0] s320;
  reg [37:0] s321;
  wire s322;
  wire [37:0] s323;
  wire [37:0] s324;
  wire [37:0] s325;
  reg [37:0] s326;
  wire [37:0] s327;
  wire [37:0] s328;
  reg [37:0] s329;
  wire [71:0] s330;
  reg [75:0] s331;
  reg [37:0] s332;
  reg [37:0] s333;
  reg [33:0] s335 [28:0];
  wire [33:0] s334;
  wire [37:0] s336;
  wire [37:0] s337;
  wire [37:0] s338;
  wire [37:0] s339;
  wire [37:0] s340;
  reg s342;
  reg s341;
  reg [37:0] s343;
  reg [37:0] s344;
  wire [37:0] s345;
  reg [37:0] s346;
  wire [37:0] s347;
  wire [37:0] s348;
  reg [37:0] s349;
  reg [37:0] s350;
  reg [37:0] s352;
  reg [37:0] s351;
  reg [37:0] s354;
  reg [37:0] s353;
  reg [75:0] s355;
  reg [37:0] s356;
  reg [37:0] s357;
  wire [71:0] s358;
  reg [37:0] s359;
  reg [1:0] s361 [11:0];
  wire [1:0] s360;
  reg [75:0] s362;
  reg [37:0] s363;
  reg [37:0] s364;
  wire [37:0] s365;
  wire [37:0] s366;
  wire [37:0] s367;
  wire [37:0] s368;
  reg [37:0] s369;
  reg [37:0] s370;
  reg [37:0] s371;
  wire [33:0] s372;
  wire [37:0] s373;
  reg [1:0] s374;
  wire [37:0] s375;
  wire [33:0] s376;
  wire [37:0] s377;
  reg [33:0] s378;
  wire [37:0] s379;
  wire [37:0] s380;
  wire [37:0] s381;
  reg [75:0] s383 [3:0]; // synthesis attribute ram_style of s383 is block
  reg [75:0] s382;
  wire [37:0] s384;
  wire [37:0] s385;
  wire [33:0] s386;
  reg [37:0] s387;
  wire [33:0] s388;
  reg [37:0] s390 [2:0];
  wire [37:0] s389;
  reg [37:0] s392 [2:0];
  wire [37:0] s391;
  wire [1:0] s393;
  wire [71:0] s394;
  wire [37:0] s395;
  reg [37:0] s396;
  reg [1:0] s397;
  wire [37:0] s398;
  wire [37:0] s399;
  wire [37:0] s400;
  wire [37:0] s401;
  wire [37:0] s402;
  wire [37:0] s403;
  wire [71:0] s404;
  reg [37:0] s405;
  wire [37:0] s406;
  reg [1:0] s408 [11:0];
  wire [1:0] s407;
  reg [37:0] s409;
  reg [1:0] s411 [11:0];
  wire [1:0] s410;
  reg [37:0] s412;
  reg [33:0] s413;
  wire [71:0] s414;
  reg [37:0] s416 [2:0];
  wire [37:0] s415;
  reg [33:0] s417;
  wire [75:0] s418;
  wire [37:0] s419;
  reg s421 [6:0];
  wire s420;
  reg [33:0] s422;
  wire [37:0] s423;
  wire [1:0] s424;
  reg [75:0] s426 [3:0]; // synthesis attribute ram_style of s426 is block
  reg [75:0] s425;
  wire [75:0] s427;
  wire [75:0] s428;
  wire [33:0] s429;
  reg [1:0] s431 [5:0];
  wire [1:0] s430;
  reg [37:0] s433 [2:0];
  wire [37:0] s432;
  wire [37:0] s434;
  wire [37:0] s435;
  wire [37:0] s436;
  wire [37:0] s437;
  wire [37:0] s438;
  wire [33:0] s439;
  reg [1:0] s440;
  reg [33:0] s441;
  wire [75:0] s442;
  wire [37:0] s443;
  reg [37:0] s444;
  reg s445;
  reg [75:0] s447 [3:0]; // synthesis attribute ram_style of s447 is block
  reg [75:0] s446;
  reg [75:0] s448;
  wire [37:0] s449;
  wire [37:0] s450;
  wire [75:0] s451;
  reg [37:0] s452;
  wire [37:0] s453;
  wire [37:0] s454;
  wire [37:0] s455;
  wire [37:0] s456;
  wire [37:0] s457;
  wire [37:0] s458;
  reg [37:0] s459;
  reg [37:0] s461 [2:0];
  wire [37:0] s460;
  wire [37:0] s462;
  wire [75:0] s463;
  reg [37:0] s464;
  reg [37:0] s466 [2:0];
  wire [37:0] s465;
  reg [37:0] s467;
  reg [37:0] s468;
  reg [37:0] s469;
  wire [37:0] s470;
  wire [71:0] s471;
  wire [37:0] s472;
  wire [37:0] s473;
  wire [37:0] s474;
  reg [37:0] s475;
  wire [1:0] s476;
  reg [37:0] s477;
  reg [1:0] s478;
  reg [37:0] s479;
  reg s481 [6:0];
  wire s480;
  reg [33:0] s482;
  wire [37:0] s483;
  reg [37:0] s485 [2:0];
  wire [37:0] s484;
  wire [37:0] s486;
  wire [37:0] s487;
  wire [1:0] s488;
  reg [37:0] s489;
  reg [33:0] s490;
  wire [37:0] s491;
  wire [37:0] s492;
  reg [1:0] s493;
  wire [37:0] s494;
  wire [37:0] s495;
  wire s496;
  wire [75:0] s497;
  reg [37:0] s498;
  wire [75:0] s499;
  wire [1:0] s500;
  reg [37:0] s502 [2:0];
  wire [37:0] s501;
  wire [33:0] s503;
  wire [33:0] s504;
  reg s506 [3:0];
  wire s505;
  wire [33:0] s507;
  reg [37:0] s508;
  reg [37:0] s509;
  wire [37:0] s510;
  wire [37:0] s511;
  wire [37:0] s512;
  reg [37:0] s513;
  reg [37:0] s514;
  reg [37:0] s515;
  reg [37:0] s516;
  reg [37:0] s517;
  wire [37:0] s518;
  wire [37:0] s519;
  reg [37:0] s521 [2:0];
  wire [37:0] s520;
  wire [37:0] s522;
  wire [37:0] s523;
  wire [37:0] s524;
  reg [33:0] s525;
  wire [37:0] s526;
  wire [37:0] s527;
  reg [37:0] s528;
  reg [37:0] s529;
  wire [37:0] s530;
  wire [71:0] s531;
  reg [1:0] s532;
  wire [37:0] s533;
  wire [37:0] s534;
  wire [71:0] s535;
  reg [37:0] s536;
  reg [37:0] s537;
  reg [37:0] s539;
  reg [37:0] s538;
  reg [37:0] s541;
  reg [37:0] s540;
  reg [37:0] s542;
  wire [71:0] s543;
  reg [37:0] s544;
  reg [37:0] s545;
  reg [37:0] s546;
  wire [37:0] s547;
  wire [37:0] s548;
  wire [37:0] s549;
  reg [37:0] s550;
  reg [37:0] s551;
  wire [37:0] s552;
  reg [37:0] s553;
  wire [71:0] s554;
  reg [37:0] s556 [2:0];
  wire [37:0] s555;
  reg s558;
  reg s557;
  wire [37:0] s559;
  wire [37:0] s560;
  wire [37:0] s561;
  reg [33:0] s562;
  wire [37:0] s563;
  reg [37:0] s564;
  reg [37:0] s565;
  reg [37:0] s566;
  wire [37:0] s567;
  wire [37:0] s568;
  reg [37:0] s569;
  reg [37:0] s570;
  reg [37:0] s572 [2:0];
  wire [37:0] s571;
  wire [71:0] s573;
  wire [37:0] s574;
  wire [71:0] s575;
  reg [37:0] s576;
  wire [37:0] s577;
  wire [37:0] s578;
  reg [37:0] s579;
  wire [37:0] s580;
  reg s582 [8:0];
  wire s581;
  reg [37:0] s583;
  reg [1:0] s584;
  reg [75:0] s586 [3:0]; // synthesis attribute ram_style of s586 is block
  reg [75:0] s585;
  reg [37:0] s587;
  reg [37:0] s588;
  reg [33:0] s589;
  reg [37:0] s590;
  wire [37:0] s591;
  wire [37:0] s592;
  wire [37:0] s593;
  wire [37:0] s594;
  wire [37:0] s595;
  reg [37:0] s596;
  reg [37:0] s597;
  wire [37:0] s598;
  reg [37:0] s600 [3:0];
  wire [37:0] s599;
  reg [37:0] s601;
  reg [33:0] s602;
  reg [37:0] s603;
  reg [37:0] s604;
  wire [37:0] s605;
  wire [1:0] s606;
  reg [37:0] s607;
  reg [37:0] s609 [2:0];
  wire [37:0] s608;
  wire [71:0] s610;
  wire [1:0] s611;
  wire [1:0] s612;
  reg [33:0] s613;
  reg [33:0] s614;
  wire [37:0] s615;
  wire [37:0] s616;
  reg [37:0] s617;
  wire [37:0] s618;
  reg [33:0] s619;
  wire [1:0] s620;
  reg [37:0] s621;
  reg [37:0] s622;
  reg [33:0] s623;
  wire [37:0] s624;
  reg [37:0] s626 [4:0];
  wire [37:0] s625;
  reg [37:0] s628 [4:0];
  wire [37:0] s627;
  wire [71:0] s629;
  wire [37:0] s630;
  wire [37:0] s631;
  wire [37:0] s632;
  reg [37:0] s634 [2:0];
  wire [37:0] s633;
  wire [37:0] s635;
  wire [37:0] s636;
  wire [37:0] s637;
  reg s639 [4:0];
  wire s638;
  wire [75:0] s640;
  wire [37:0] s641;
  wire [37:0] s642;
  wire [37:0] s643;
  wire [37:0] s644;
  reg [37:0] s645;
  reg [37:0] s646;
  reg [37:0] s647;
  reg [37:0] s649 [2:0];
  wire [37:0] s648;
  reg [1:0] s650;
  reg [37:0] s651;
  reg [37:0] s652;
  wire [75:0] s653;
  reg [33:0] s654;
  reg [33:0] s655;
  wire [37:0] s656;
  reg [37:0] s658;
  reg [37:0] s657;
  reg [37:0] s660;
  reg [37:0] s659;
  reg [75:0] s662 [3:0]; // synthesis attribute ram_style of s662 is block
  reg [75:0] s661;
  reg [37:0] s663;
  reg [37:0] s664;
  reg [75:0] s666 [3:0]; // synthesis attribute ram_style of s666 is block
  reg [75:0] s665;
  wire [37:0] s667;
  reg [75:0] s668;
  wire [37:0] s669;
  wire s670;
  integer i;
  assign s8 = s89 + s357;
  assign s11 = s82 ? s346 : s262;
  assign s16 = s629[69:32];
  assign s17 = s230 + s66;
  assign s19 = s397[0];
  assign s23 = s24 [2];
  assign s25 = {s70, s597};
  assign s27 = s432 - s263;
  assign s28 = s29 [37];
  assign s35 = s505 ? s350 : s275;
  assign s36 = {s603, s604};
  assign s37 = s362[75:38];
  assign s38 = s362[37:0];
  assign s40 = s550 - s369;
  assign s41 = s551 - s370;
  assign s42 = s648 + s263;
  assign s43 = $signed(s107) * $signed(s165);
  assign s48 = s111 ? s122 : s459;
  assign s53 = s54 [2];
  assign s55 = s557 ? s301 : s657;
  assign s56 = s557 ? s303 : s659;
  assign s57 = s58 [18];
  assign s60 = s117[37:0];
  assign s61 = s117[75:38];
  assign s64 = s65 [18];
  assign s67 = s331[37:0];
  assign s68 = s331[75:38];
  assign s71 = s72 [3];
  assign s74 = s75 [2];
  assign s76 = $signed(s664) * $signed(s417);
  assign s78 = $signed(s299) * $signed(s97);
  assign s81 = s430[1];
  assign s82 = s430[0];
  assign s83 = s84 [9];
  assign s85 = s581 ? s528 : s343;
  assign s86 = s581 ? s529 : s344;
  assign s87 = s544 + s294;
  assign s88 = s545 + s295;
  assign s90 = s71 ? s44 : s224;
  assign s91 = s555 + s231;
  assign s92 = s581 ? s343 : s528;
  assign s93 = s581 ? s344 : s529;
  assign s94 = s505 ? s164 : s79;
  assign s95 = s394[69:32];
  assign s98 = s106[37:0];
  assign s99 = s106[75:38];
  assign s100 = s613 + s26;
  assign s101 = s102 [8];
  assign s103 = s420 ? s615 : s522;
  assign s104 = s420 ? s616 : s523;
  assign s107 = s108 [2];
  assign s109 = s110 [2];
  assign s112 = s111 ? s178 : s152;
  assign s114 = s132 - s50;
  assign s115 = s116 [6];
  assign o3 = s36;
  assign s118 = s83 ? s612 : s397;
  assign s119 = s274[75:38];
  assign s120 = s274[37:0];
  assign o1 = s146;
  assign s121 = s181[69:32];
  assign s124 = s125 [11];
  assign s126 = s236 ? s1 : s253;
  assign s127 = s128 [3];
  assign s129 = s480 ? s534 : s137;
  assign s130 = s480 ? s533 : s136;
  assign s134 = {s498, s409};
  assign s135 = s71 ? s45 : s222;
  assign s136 = s668[75:38];
  assign s137 = s668[37:0];
  assign s140 = s141 [4];
  assign s142 = s143 [4];
  assign s146 = {s651, s652};
  assign s148 = s149 [22];
  assign s150 = s155[69:32];
  assign s153 = s480 ? s210 : s241;
  assign s154 = s480 ? s209 : s240;
  assign s155 = $signed(s516) * $signed(s562);
  assign s158 = s490 - s131;
  assign s161 = s236 ? s253 : s1;
  assign s162 = s163 [18];
  assign s166 = s133 - s517;
  assign s167 = s82 ? s356 : s513;
  assign s174 = s505 ? s49 : s59;
  assign s175 = s176 [18];
  assign s181 = $signed(s452) * $signed(s62);
  assign s182 = s111 ? s459 : s122;
  assign s183 = s505 ? s275 : s350;
  assign s184 = s185 [9];
  assign s186 = s71 ? s188 : s225;
  assign s194 = s505 ? s59 : s49;
  assign s198 = s133 + s517;
  assign s201 = s581 ? s179 : s332;
  assign s202 = s581 ? s180 : s333;
  assign s203 = s184 ? s590 : s279;
  assign s207 = s208 [2];
  assign s209 = s448[75:38];
  assign s210 = s448[37:0];
  assign s214 = s573[69:32];
  assign s215 = s445 ? s476 : s430;
  assign s216 = s217 [8];
  assign s218 = s505 ? s260 : s7;
  assign s219 = s236 ? s123 : s542;
  assign s220 = s608 - s74;
  assign s231 = s232 [2];
  assign s233 = s236 ? s542 : s123;
  assign s236 = s237 [2];
  assign s239 = $signed(s223) * $signed(s614);
  assign s240 = s170[75:38];
  assign s241 = s170[37:0];
  assign s242 = s414[69:32];
  assign s246 = s396 - s39;
  assign s248 = s613 - s26;
  assign s249 = s193 + s623;
  assign s251 = s101 ? s51 : s138;
  assign s252 = s101 ? s52 : s139;
  assign s258 = s82 ? s601 : s469;
  assign s259 = s531[69:32];
  assign s261 = s111 ? s579 : s46;
  assign s263 = s264 [2];
  assign s265 = s184 ? s546 : s479;
  assign s269 = s270 [2];
  assign s272 = s581 ? s332 : s179;
  assign s273 = s581 ? s333 : s180;
  assign s277 = {s371, s553};
  assign s278 = s32 + s663;
  assign s281 = s20 - s583;
  assign s284 = s115 ? s119 : s526;
  assign s285 = s115 ? s120 : s527;
  assign s287 = s505 ? s7 : s260;
  assign s290 = s554[69:32];
  assign s291 = s111 ? s200 : s647;
  assign s296 = s111 ? s647 : s200;
  assign s298 = s82 ? s587 : s489;
  assign s305 = $signed(s300) * $signed(s14);
  assign s306 = s115 ? s560 : s37;
  assign s307 = s115 ? s561 : s38;
  assign s308 = s309 [27];
  assign s311 = s71 ? s222 : s45;
  assign s313 = s43[69:32];
  assign s314 = s501 + s465;
  assign s315 = {s21, s405};
  assign s316 = s514 + s475;
  assign s322 = s83 ? s496 : s445;
  assign o0 = s428;
  assign s323 = s269 + s460;
  assign s324 = s297 + s69;
  assign s325 = s305[69:32];
  assign s327 = s471[69:32];
  assign s328 = s98 - s99;
  assign s330 = $signed(s363) * $signed(s235);
  assign s334 = s335 [28];
  assign s336 = s330[69:32];
  assign s337 = s557 ? s254 : s189;
  assign s338 = s557 ? s256 : s191;
  assign s339 = s565 - s566;
  assign s340 = s184 ? s279 : s590;
  assign s345 = s82 ? s469 : s601;
  assign s347 = s101 ? s196 : s292;
  assign s348 = s101 ? s197 : s293;
  assign s358 = $signed(s229) * $signed(s73);
  assign s360 = s361 [11];
  assign s365 = s184 ? s329 : s238;
  assign s366 = s244 + s645;
  assign s367 = s245 + s646;
  assign s368 = s396 + s39;
  assign s372 = s193 - s623;
  assign s373 = s71 ? s225 : s188;
  assign s375 = s236 ? s387 : s464;
  assign s376 = s308 + s334;
  assign s377 = s184 ? s238 : s329;
  assign s379 = s341 ? s570 : s283;
  assign s380 = s341 ? s569 : s282;
  assign s381 = s543[69:32];
  assign s384 = s420 ? s635 : s548;
  assign s385 = s420 ? s636 : s549;
  assign s386 = s422 - s250;
  assign s388 = s482 - s15;
  assign o2 = s463;
  assign s389 = s390 [2];
  assign s391 = s392 [2];
  assign s393 = s532 ^ s31;
  assign s394 = $signed(s317) * $signed(s276);
  assign s395 = s520 - s484;
  assign s398 = s115 ? s526 : s119;
  assign s399 = s115 ? s527 : s120;
  assign s400 = s508 - s509;
  assign s401 = s111 ? s46 : s579;
  assign s402 = s115 ? s37 : s560;
  assign s403 = s115 ? s38 : s561;
  assign s404 = $signed(s227) * $signed(s525);
  assign s406 = s184 ? s479 : s546;
  assign s407 = s408 [11];
  assign s410 = s411 [11];
  assign s414 = $signed(s364) * $signed(s441);
  assign s415 = s416 [2];
  assign s418 = {s47, s444};
  assign s419 = s82 ? s513 : s356;
  assign s420 = s421 [6];
  assign s423 = s514 - s475;
  assign s424 = s19 ? 2'd3 : 2'd0;
  assign s427 = {s607, s359};
  assign s428 = {s266, s267};
  assign s429 = s230 - s66;
  assign s430 = s431 [5];
  assign s432 = s433 [2];
  assign s434 = s101 ? s138 : s51;
  assign s435 = s101 ? s139 : s52;
  assign s436 = s341 ? s538 : s351;
  assign s437 = s341 ? s540 : s353;
  assign s438 = s594 - s595;
  assign s439 = s482 + s15;
  assign s442 = {s234, s576};
  assign s443 = s71 ? s187 : s221;
  assign s449 = s550 + s369;
  assign s450 = s551 + s370;
  assign s451 = {s310, s286};
  assign s453 = s610[69:32];
  assign s454 = 38'd0 - s571;
  assign s455 = s172 + s621;
  assign s456 = s173 + s622;
  assign s457 = s638 ? s140 : s625;
  assign s458 = s638 ? s142 : s627;
  assign s460 = s461 [2];
  assign s462 = s67 - s68;
  assign s463 = {s536, s537};
  assign s465 = s466 [2];
  assign s470 = s358[69:32];
  assign s471 = $signed(s151) * $signed(s147);
  assign s472 = s633 - s460;
  assign s473 = s389 + s74;
  assign s474 = s184 ? s199 : s321;
  assign s476 = {s82, s81};
  assign s480 = s481 [6];
  assign s483 = s535[69:32];
  assign s484 = s485 [2];
  assign s486 = s244 - s645;
  assign s487 = s245 - s646;
  assign s488 = next ? 2'd0 : s611;
  assign s491 = s545 - s295;
  assign s492 = s544 - s294;
  assign s494 = s420 ? s548 : s635;
  assign s495 = s420 ? s549 : s636;
  assign s496 = s445 + 1'd1;
  assign s497 = {s63, s617};
  assign s499 = {s477, s22};
  assign s500 = s478 ^ s31;
  assign s501 = s502 [2];
  assign s503 = s308 - s334;
  assign s504 = s490 + s131;
  assign s505 = s506 [3];
  assign s507 = s422 + s250;
  assign s510 = s216 ? s9 : s288;
  assign s511 = s216 ? s10 : s289;
  assign s512 = s20 + s583;
  assign s518 = s101 ? s292 : s196;
  assign s519 = s101 ? s293 : s197;
  assign s520 = s521 [2];
  assign s522 = s6[75:38];
  assign s523 = s6[37:0];
  assign s524 = s71 ? s221 : s187;
  assign s526 = s168[75:38];
  assign s527 = s168[37:0];
  assign s530 = s236 ? s464 : s387;
  assign s531 = $signed(s318) * $signed(s378);
  assign s533 = s268[75:38];
  assign s534 = s268[37:0];
  assign s535 = $signed(s349) * $signed(34'd3037000499);
  assign s543 = $signed(s468) * $signed(s80);
  assign s547 = s391 - s231;
  assign s548 = s195[75:38];
  assign s549 = s195[37:0];
  assign s552 = s415 + s484;
  assign s554 = $signed(s515) * $signed(s319);
  assign s555 = s556 [2];
  assign s559 = s184 ? s321 : s199;
  assign s560 = s177[75:38];
  assign s561 = s177[37:0];
  assign next_out = s28;
  assign s563 = s505 ? s79 : s164;
  assign s567 = s76[69:32];
  assign s568 = s32 - s663;
  assign s571 = s572 [2];
  assign s573 = $signed(s271) * $signed(s654);
  assign s574 = s575[69:32];
  assign s575 = $signed(s467) * $signed(s243);
  assign s577 = s239[69:32];
  assign s578 = s207 - s171;
  assign s580 = s404[69:32];
  assign s581 = s582 [8];
  assign s591 = s23 + s171;
  assign s592 = s236 ? s412 : s145;
  assign s593 = s78[69:32];
  assign s594 = s211[37:0];
  assign s595 = s211[75:38];
  assign s598 = s132 + s50;
  assign s599 = s600 [3];
  assign s605 = s297 - s69;
  assign s606 = reset ? 2'd0 : s118;
  assign s608 = s609 [2];
  assign s610 = $signed(s228) * $signed(s212);
  assign s611 = s584 + 2'd1;
  assign s612 = s397 + 2'd1;
  assign s615 = s355[75:38];
  assign s616 = s355[37:0];
  assign s618 = s82 ? s489 : s587;
  assign s620 = s206 ^ s31;
  assign s624 = s82 ? s262 : s346;
  assign s625 = s626 [4];
  assign s627 = s628 [4];
  assign s629 = $signed(s109) * $signed(s602);
  assign s630 = s53 - s465;
  assign s631 = s60 - s61;
  assign s632 = s236 ? s145 : s412;
  assign s633 = s634 [2];
  assign s635 = s280[75:38];
  assign s636 = s280[37:0];
  assign s637 = s156 - s157;
  assign s638 = s639 [4];
  assign s640 = {s326, s96};
  assign s641 = s420 ? s522 : s615;
  assign s642 = s420 ? s523 : s616;
  assign s643 = s172 - s621;
  assign s644 = s173 - s622;
  assign s648 = s649 [2];
  assign s653 = {s596, s144};
  assign s656 = s89 - s357;
  assign s667 = s111 ? s152 : s178;
  assign s669 = s71 ? s224 : s44;
  assign s670 = reset ? 1'd0 : s322;
  always @(*)
    case(s148)
      0: s18 = 34'd4294967296;
      1: s18 = 34'd1643612826;
      2: s18 = 34'd14142868685;
      3: s18 = 34'd13211836807;
    endcase
  always @(*)
    case(s584)
      0: s30 = 34'd420980412;
      1: s30 = 34'd2024633568;
      2: s30 = 34'd3320054616;
      3: s30 = 34'd4110027446;
    endcase
  always @(*)
    case(s584)
      0: s77 = 34'd0;
      1: s77 = 34'd1643612826;
      2: s77 = 34'd3037000499;
      3: s77 = 34'd3968032377;
    endcase
  always @(*)
    case(s397)
      0: s105 = 2'd0;
      1: s105 = 2'd1;
      2: s105 = 2'd3;
      3: s105 = 2'd2;
    endcase
  always @(*)
    case(s584)
      0: s113 = 34'd1246763195;
      1: s113 = 34'd2724698408;
      2: s113 = 34'd3787822988;
      3: s113 = 34'd4274285854;
    endcase
  always @(*)
    case(s127)
      0: s169 = s599;
      1: s169 = s564;
      2: s169 = 38'd0;
      3: s169 = s588;
    endcase
  always @(*)
    case(s584)
      0: s213 = 34'd4110027446;
      1: s213 = 34'd3320054616;
      2: s213 = 34'd2024633568;
      3: s213 = 34'd420980412;
    endcase
  always @(*)
    case(s148)
      0: s226 = 34'd4294967296;
      1: s226 = 34'd3037000499;
      2: s226 = 34'd0;
      3: s226 = 34'd14142868685;
    endcase
  always @(*)
    case(s584)
      0: s247 = 34'd4294967296;
      1: s247 = 34'd3968032377;
      2: s247 = 34'd3037000499;
      3: s247 = 34'd1643612826;
    endcase
  always @(*)
    case(s584)
      0: s312 = 34'd837906552;
      1: s312 = 34'd2386155981;
      2: s312 = 34'd3571134792;
      3: s312 = 34'd4212440703;
    endcase
  always @(*)
    case(s397)
      0: s374 = 2'd0;
      1: s374 = 2'd2;
      2: s374 = 2'd3;
      3: s374 = 2'd1;
    endcase
  always @(*)
    case(s584)
      0: s413 = 34'd4212440703;
      1: s413 = 34'd3571134792;
      2: s413 = 34'd2386155981;
      3: s413 = 34'd837906552;
    endcase
  always @(*)
    case(s148)
      0: s589 = 34'd0;
      1: s589 = 34'd13211836807;
      2: s589 = 34'd14142868685;
      3: s589 = 34'd1643612826;
    endcase
  always @(*)
    case(s148)
      0: s619 = 34'd0;
      1: s619 = 34'd3037000499;
      2: s619 = 34'd4294967296;
      3: s619 = 34'd3037000499;
    endcase
  always @(*)
    case(s584)
      0: s655 = 34'd4274285854;
      1: s655 = 34'd3787822988;
      2: s655 = 34'd2724698408;
      3: s655 = 34'd1246763195;
    endcase
  always @(posedge clk)
    begin
      s1 <= s281;
      s3 [s650] <= s640;
      s2 <= s3 [s650];
      s5 [s320] <= s427;
      s4 <= s5 [s320];
      s6 <= s425;
      s7 <= s135;
      s9 <= s129;
      s10 <= s130;
      s13 [s360] <= s451;
      s12 <= s13 [s360];
      s14 <= s372;
      s15 <= s30;
      s20 <= s114;
      s21 <= s563;
      s22 <= s287;
      s24 [0] <= s16;
      for (i = 1; i < 3; i = i + 1)
        s24 [i] <= s24 [i - 1];
      s26 <= s113;
      s29 [0] <= s83;
      for (i = 1; i < 38; i = i + 1)
        s29 [i] <= s29 [i - 1];
      s31 <= s215;
      s32 <= s472;
      s34 [s57] <= s497;
      s33 <= s34 [s57];
      s39 <= s316;
      s44 <= s643;
      s45 <= s644;
      s46 <= s618;
      s47 <= s365;
      s49 <= s311;
      s50 <= s578;
      s51 <= s402;
      s52 <= s403;
      s54 [0] <= s580;
      for (i = 1; i < 3; i = i + 1)
        s54 [i] <= s54 [i - 1];
      s58 [0] <= s407;
      for (i = 1; i < 19; i = i + 1)
        s58 [i] <= s58 [i - 1];
      s59 <= s373;
      s62 <= s193;
      s63 <= s406;
      s65 [0] <= s410;
      for (i = 1; i < 19; i = i + 1)
        s65 [i] <= s65 [i - 1];
      s66 <= s619;
      s69 <= s568;
      s70 <= s261;
      s72 [0] <= s420;
      for (i = 1; i < 4; i = i + 1)
        s72 [i] <= s72 [i - 1];
      s73 <= s613;
      s75 [0] <= s214;
      for (i = 1; i < 3; i = i + 1)
        s75 [i] <= s75 [i - 1];
      s79 <= s524;
      s80 <= s248;
      s84 [0] <= next;
      for (i = 1; i < 10; i = i + 1)
        s84 [i] <= s84 [i - 1];
      s89 <= s598;
      s96 <= s291;
      s97 <= s249;
      s102 [0] <= s236;
      for (i = 1; i < 9; i = i + 1)
        s102 [i] <= s102 [i - 1];
      s106 <= i2;
      s108 [0] <= s565;
      for (i = 1; i < 3; i = i + 1)
        s108 [i] <= s108 [i - 1];
      s110 [0] <= s566;
      for (i = 1; i < 3; i = i + 1)
        s110 [i] <= s110 [i - 1];
      s111 <= s81;
      s116 [0] <= s184;
      for (i = 1; i < 7; i = i + 1)
        s116 [i] <= s116 [i - 1];
      s117 <= i3;
      s122 <= s419;
      s123 <= s656;
      s125 [0] <= s440;
      for (i = 1; i < 12; i = i + 1)
        s125 [i] <= s125 [i - 1];
      s128 [0] <= s148;
      for (i = 1; i < 4; i = i + 1)
        s128 [i] <= s128 [i - 1];
      s131 <= s589;
      s132 <= s457;
      s133 <= s458;
      s138 <= s398;
      s139 <= s399;
      s141 [0] <= s282;
      for (i = 1; i < 5; i = i + 1)
        s141 [i] <= s141 [i - 1];
      s143 [0] <= s283;
      for (i = 1; i < 5; i = i + 1)
        s143 [i] <= s143 [i - 1];
      s144 <= s218;
      s145 <= s605;
      s147 <= s482;
      s149 [0] <= s430;
      for (i = 1; i < 23; i = i + 1)
        s149 [i] <= s149 [i - 1];
      s151 <= s438;
      s152 <= s345;
      s156 <= s379;
      s157 <= s380;
      s160 [s64] <= s134;
      s159 <= s160 [s64];
      s163 [0] <= s360;
      for (i = 1; i < 19; i = i + 1)
        s163 [i] <= s163 [i - 1];
      s164 <= s669;
      s165 <= s429;
      s168 <= s585;
      s170 <= s661;
      s171 <= s169;
      s172 <= s40;
      s173 <= s41;
      s176 [0] <= s124;
      for (i = 1; i < 19; i = i + 1)
        s176 [i] <= s176 [i - 1];
      s177 <= s382;
      s178 <= s624;
      s179 <= s494;
      s180 <= s495;
      s185 [0] <= s557;
      for (i = 1; i < 10; i = i + 1)
        s185 [i] <= s185 [i - 1];
      s187 <= s455;
      s188 <= s456;
      s190 <= s533;
      s189 <= s190;
      s192 <= s534;
      s191 <= s192;
      s193 <= s413;
      s195 <= s4;
      s196 <= s284;
      s197 <= s285;
      s199 <= s530;
      s200 <= s258;
      s205 [s493] <= s25;
      s204 <= s205 [s493];
      s206 <= s424;
      s208 [0] <= s313;
      for (i = 1; i < 3; i = i + 1)
        s208 [i] <= s208 [i - 1];
      s211 <= i1;
      s212 <= s388;
      s217 [0] <= s71;
      for (i = 1; i < 9; i = i + 1)
        s217 [i] <= s217 [i - 1];
      s221 <= s486;
      s222 <= s487;
      s223 <= s637;
      s224 <= s366;
      s225 <= s367;
      s227 <= s594;
      s228 <= s595;
      s229 <= s631;
      s230 <= s226;
      s232 [0] <= s567;
      for (i = 1; i < 3; i = i + 1)
        s232 [i] <= s232 [i - 1];
      s234 <= s265;
      s235 <= s507;
      s237 [0] <= s638;
      for (i = 1; i < 3; i = i + 1)
        s237 [i] <= s237 [i - 1];
      s238 <= s126;
      s243 <= s100;
      s244 <= s449;
      s245 <= s450;
      s250 <= s77;
      s253 <= s8;
      s255 <= s136;
      s254 <= s255;
      s257 <= s137;
      s256 <= s257;
      s260 <= s186;
      s262 <= s552;
      s264 [0] <= s470;
      for (i = 1; i < 3; i = i + 1)
        s264 [i] <= s264 [i - 1];
      s266 <= s434;
      s267 <= s435;
      s268 <= s665;
      s270 [0] <= s259;
      for (i = 1; i < 3; i = i + 1)
        s270 [i] <= s270 [i - 1];
      s271 <= s462;
      s274 <= s159;
      s275 <= s90;
      s276 <= s503;
      s279 <= s592;
      s280 <= s204;
      s282 <= s337;
      s283 <= s338;
      s286 <= s194;
      s288 <= s153;
      s289 <= s154;
      s292 <= s306;
      s293 <= s307;
      s294 <= s272;
      s295 <= s273;
      s297 <= s166;
      s299 <= s98;
      s300 <= s99;
      s302 <= s240;
      s301 <= s302;
      s304 <= s241;
      s303 <= s304;
      s309 [0] <= s654;
      for (i = 1; i < 28; i = i + 1)
        s309 [i] <= s309 [i - 1];
      s310 <= s94;
      s317 <= s156;
      s318 <= s157;
      s319 <= s504;
      s320 <= s393;
      s321 <= s632;
      s326 <= s401;
      s329 <= s233;
      s331 <= i0;
      s332 <= s641;
      s333 <= s642;
      s335 [0] <= s250;
      for (i = 1; i < 29; i = i + 1)
        s335 [i] <= s335 [i - 1];
      s342 <= s216;
      s341 <= s342;
      s343 <= s103;
      s344 <= s104;
      s346 <= s473;
      s349 <= s339;
      s350 <= s443;
      s352 <= s9;
      s351 <= s352;
      s354 <= s10;
      s353 <= s354;
      s355 <= s2;
      s356 <= s220;
      s357 <= s278;
      s359 <= s112;
      s361 [0] <= s493;
      for (i = 1; i < 12; i = i + 1)
        s361 [i] <= s361 [i - 1];
      s362 <= s33;
      s363 <= s67;
      s364 <= s68;
      s369 <= s92;
      s370 <= s93;
      s371 <= s182;
      s378 <= s376;
      s383 [s175] <= s418;
      s382 <= s383 [s175];
      s387 <= s368;
      s390 [0] <= s242;
      for (i = 1; i < 3; i = i + 1)
        s390 [i] <= s390 [i - 1];
      s392 [0] <= s290;
      for (i = 1; i < 3; i = i + 1)
        s392 [i] <= s392 [i - 1];
      s396 <= s198;
      s397 <= s606;
      s405 <= s174;
      s408 [0] <= s320;
      for (i = 1; i < 12; i = i + 1)
        s408 [i] <= s408 [i - 1];
      s409 <= s474;
      s411 [0] <= s650;
      for (i = 1; i < 12; i = i + 1)
        s411 [i] <= s411 [i - 1];
      s412 <= s246;
      s416 [0] <= s325;
      for (i = 1; i < 3; i = i + 1)
        s416 [i] <= s416 [i - 1];
      s417 <= s490;
      s421 [0] <= s111;
      for (i = 1; i < 7; i = i + 1)
        s421 [i] <= s421 [i - 1];
      s422 <= s247;
      s426 [s440] <= s277;
      s425 <= s426 [s440];
      s431 [0] <= s584;
      for (i = 1; i < 6; i = i + 1)
        s431 [i] <= s431 [i - 1];
      s433 [0] <= s574;
      for (i = 1; i < 3; i = i + 1)
        s433 [i] <= s433 [i - 1];
      s440 <= s31;
      s441 <= s386;
      s444 <= s559;
      s445 <= s670;
      s447 [s124] <= s499;
      s446 <= s447 [s124];
      s448 <= s446;
      s452 <= s328;
      s459 <= s298;
      s461 [0] <= s577;
      for (i = 1; i < 3; i = i + 1)
        s461 [i] <= s461 [i - 1];
      s464 <= s324;
      s466 [0] <= s327;
      for (i = 1; i < 3; i = i + 1)
        s466 [i] <= s466 [i - 1];
      s467 <= s60;
      s468 <= s61;
      s469 <= s42;
      s475 <= s91;
      s477 <= s35;
      s478 <= s105;
      s479 <= s219;
      s481 [0] <= s505;
      for (i = 1; i < 7; i = i + 1)
        s481 [i] <= s481 [i - 1];
      s482 <= s655;
      s485 [0] <= s121;
      for (i = 1; i < 3; i = i + 1)
        s485 [i] <= s485 [i - 1];
      s489 <= s630;
      s490 <= s18;
      s493 <= s620;
      s498 <= s377;
      s502 [0] <= s453;
      for (i = 1; i < 3; i = i + 1)
        s502 [i] <= s502 [i - 1];
      s506 [0] <= s581;
      for (i = 1; i < 4; i = i + 1)
        s506 [i] <= s506 [i - 1];
      s508 <= s436;
      s509 <= s437;
      s513 <= s395;
      s514 <= s323;
      s515 <= s508;
      s516 <= s509;
      s517 <= s591;
      s521 [0] <= s593;
      for (i = 1; i < 3; i = i + 1)
        s521 [i] <= s521 [i - 1];
      s525 <= s439;
      s528 <= s384;
      s529 <= s385;
      s532 <= s374;
      s536 <= s347;
      s537 <= s348;
      s539 <= s288;
      s538 <= s539;
      s541 <= s289;
      s540 <= s541;
      s542 <= s512;
      s544 <= s201;
      s545 <= s202;
      s546 <= s161;
      s550 <= s85;
      s551 <= s86;
      s553 <= s667;
      s556 [0] <= s150;
      for (i = 1; i < 3; i = i + 1)
        s556 [i] <= s556 [i - 1];
      s558 <= s480;
      s557 <= s558;
      s562 <= s158;
      s564 <= s571;
      s565 <= s510;
      s566 <= s511;
      s569 <= s55;
      s570 <= s56;
      s572 [0] <= s483;
      for (i = 1; i < 3; i = i + 1)
        s572 [i] <= s572 [i - 1];
      s576 <= s203;
      s579 <= s167;
      s582 [0] <= s82;
      for (i = 1; i < 9; i = i + 1)
        s582 [i] <= s582 [i - 1];
      s583 <= s423;
      s584 <= s488;
      s586 [s162] <= s442;
      s585 <= s586 [s162];
      s587 <= s27;
      s588 <= s454;
      s590 <= s375;
      s596 <= s183;
      s597 <= s296;
      s600 [0] <= s349;
      for (i = 1; i < 4; i = i + 1)
        s600 [i] <= s600 [i - 1];
      s601 <= s314;
      s602 <= s17;
      s603 <= s518;
      s604 <= s519;
      s607 <= s48;
      s609 [0] <= s336;
      for (i = 1; i < 3; i = i + 1)
        s609 [i] <= s609 [i - 1];
      s613 <= s213;
      s614 <= s308;
      s617 <= s340;
      s621 <= s491;
      s622 <= s492;
      s623 <= s312;
      s626 [0] <= s569;
      for (i = 1; i < 5; i = i + 1)
        s626 [i] <= s626 [i - 1];
      s628 [0] <= s570;
      for (i = 1; i < 5; i = i + 1)
        s628 [i] <= s628 [i - 1];
      s634 [0] <= s95;
      for (i = 1; i < 3; i = i + 1)
        s634 [i] <= s634 [i - 1];
      s639 [0] <= s341;
      for (i = 1; i < 5; i = i + 1)
        s639 [i] <= s639 [i - 1];
      s645 <= s87;
      s646 <= s88;
      s647 <= s11;
      s649 [0] <= s381;
      for (i = 1; i < 3; i = i + 1)
        s649 [i] <= s649 [i - 1];
      s650 <= s500;
      s651 <= s251;
      s652 <= s252;
      s654 <= s422;
      s658 <= s209;
      s657 <= s658;
      s660 <= s210;
      s659 <= s660;
      s662 [s407] <= s315;
      s661 <= s662 [s407];
      s663 <= s547;
      s664 <= s400;
      s666 [s410] <= s653;
      s665 <= s666 [s410];
      s668 <= s12;
    end
endmodule
