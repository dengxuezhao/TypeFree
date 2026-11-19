package com.typefree.core.ime.enhancer

import java.util.Locale

/**
 * Compact 26-way ASCII Trie of common English words (`length >= 2`) ordered by frequency
 * for fast EN-mode prefix completion (`suggestWords`).
 */
class EnglishWordTrie(words: Collection<String>) {

    private class TrieNode {
        val children = arrayOfNulls<TrieNode>(26)
        var isWord: Boolean = false
        var hasChild: Boolean = false
        var rank: Int = Int.MAX_VALUE
    }

    private val root = TrieNode()

    init {
        var idx = 0
        for (raw in words) {
            val normalized = raw.trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty() || !isAsciiLowercase(normalized)) continue
            insert(normalized, idx++)
        }
    }

    private fun insert(word: String, rank: Int) {
        var cur = root
        for (ch in word) {
            val idx = ch - 'a'
            var next = cur.children[idx]
            if (next == null) {
                next = TrieNode()
                cur.children[idx] = next
                cur.hasChild = true
            }
            cur = next
        }
        cur.isWord = true
        if (rank < cur.rank) {
            cur.rank = rank
        }
    }

    private fun findNode(prefix: String): TrieNode? {
        if (prefix.isEmpty()) return null
        var cur = root
        for (ch in prefix) {
            val lower = ch.lowercaseChar()
            if (lower !in 'a'..'z') return null
            cur = cur.children[lower - 'a'] ?: return null
        }
        return cur
    }

    fun hasLongerWordPrefix(prefix: String): Boolean {
        val node = findNode(prefix) ?: return false
        return node.hasChild
    }

    fun containsWord(word: String): Boolean {
        val node = findNode(word) ?: return false
        return node.isWord
    }

    private data class RankedWord(val word: String, val rank: Int)

    fun suggestWords(prefix: String, limit: Int = 8): List<String> {
        if (limit <= 0) return emptyList()
        val normalized = prefix.trim().lowercase(Locale.ROOT)
        val startNode = findNode(normalized) ?: return emptyList()

        val collected = ArrayList<RankedWord>(32)
        val sb = StringBuilder(normalized)
        collectSubtreeWords(startNode, sb, collected)
        if (collected.isEmpty()) return emptyList()

        collected.sortWith(
            Comparator { a, b ->
                val cmpRank = a.rank.compareTo(b.rank)
                if (cmpRank != 0) return@Comparator cmpRank
                val cmpLen = a.word.length.compareTo(b.word.length)
                if (cmpLen != 0) return@Comparator cmpLen
                a.word.compareTo(b.word)
            }
        )
        val outSize = minOf(limit, collected.size)
        val result = ArrayList<String>(outSize)
        for (i in 0 until outSize) {
            result.add(collected[i].word)
        }
        return result
    }

    private fun collectSubtreeWords(
        node: TrieNode,
        sb: StringBuilder,
        out: MutableList<RankedWord>
    ) {
        if (node.isWord) {
            out.add(RankedWord(sb.toString(), node.rank))
        }
        if (!node.hasChild) return
        for (i in 0 until 26) {
            val child = node.children[i] ?: continue
            sb.append(('a' + i))
            collectSubtreeWords(child, sb, out)
            sb.setLength(sb.length - 1)
        }
    }

    companion object {
        private fun isAsciiLowercase(s: String): Boolean {
            for (ch in s) {
                if (ch !in 'a'..'z') return false
            }
            return true
        }

        private val DEFAULT_WORDS: List<String> by lazy {
            BUILTIN_WORD_BLOB
                .lineSequence()
                .flatMap { it.splitToSequence(',', ' ', '\t') }
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.length >= 2 && isAsciiLowercase(it) }
                .distinct()
                .toList()
        }

        fun defaultBuiltIn(): EnglishWordTrie = EnglishWordTrie(DEFAULT_WORDS)

        private const val BUILTIN_WORD_BLOB = """
the,and,for,you,are,not,can,app,api,git,url,sql,code,work,time,good,make,test,user,data,file,line,list,name,plan,send,show,take,text,view,week,year
to,of,in,is,it,on,be,as,at,by,or,an,we,he,do,if,me,my,up,so,go,no,us,am,ok,hi,oh,tv,id,ip,ui,ux,ai,ml,os,db,io,vm,qa,pr,ci,cd,js,ts,py,rb,rs
that,with,have,this,will,your,from,they,know,want,been,much,some,what,when,more,than,them,then,only,look,come,over,also,back,into,just,like,make,many,most,must,need,next,only,other,our,out,own,part,same,seem,such,take,tell,very,well,were,what,when,where,which,while,who,why,will,with,word,year
all,any,but,day,did,end,far,few,get,got,had,has,her,him,his,how,its,let,man,may,men,new,now,off,old,one,our,out,own,put,ran,run,saw,say,see,set,she,six,ten,too,top,try,two,use,was,way,who,why,yes,yet
able,acid,aged,aide,area,army,away,baby,back,ball,band,bank,base,bath,bear,beat,been,beer,bell,belt,bend,best,bill,bird,bite,blow,blue,boat,body,bold,bomb,bond,bone,book,boom,boot,born,boss,both,bowl,bulk,burn,bush,busy,byte,cafe,cage,cake,call,calm,came,camp,card,care,case,cash,cast,cell,chat,chip,city,clip,club,coal,coat,coin,cold,come,cook,cool,cope,copy,core,cost,crew,crop,dark,dash,date,dawn,days,dead,deal,dean,dear,debt,deck,deep,deer,deny,desk,dial,dice,diet,diff,dirt,disc,disk,dock,does,done,door,dose,down,drag,draw,drew,drop,drug,dual,duck,duke,dull,dump,dust,duty,each,earn,ease,east,easy,edge,edit,else,emit,ends,enum,envy,epic,euro,even,ever,evil,exam,exec,exit,face,fact,fade,fail,fair,fall,fame,farm,fast,fate,fear,feed,feel,feet,fell,felt,fill,film,find,fine,fire,firm,fish,fist,five,flag,flat,flew,flow,fold,folk,fond,font,food,fool,foot,ford,fork,form,fort,foul,four,free,from,fuel,full,fund,gain,game,gang,gate,gave,gear,gene,gift,girl,give,glad,glow,goal,goes,gold,golf,gone,gray,grew,grey,grid,grip,grow,gulf,guru,guys,hack,hair,half,hall,hand,hang,hard,harm,hash,hate,have,head,heal,heap,hear,heat,held,hell,help,here,hero,hide,high,hike,hill,hint,hire,hold,hole,holy,home,hook,hope,horn,host,hour,huge,hung,hunt,hurt,icon,idea,idle,inch,info,init,into,iron,item,jack,jail,jane,jazz,jean,jobs,join,joke,json,jump,june,jury,just,keen,keep,kept,keys,kick,kids,kill,kind,king,kiss,knee,knew,knit,knot,know,lack,lady,laid,lake,lamp,land,lane,lang,last,late,laws,lazy,lead,leaf,leak,lean,leap,left,lend,lens,less,liar,lick,life,lift,like,limb,lime,link,lion,lips,live,load,loan,lock,logo,logs,lone,long,look,loop,lord,lose,loss,lost,lots,loud,love,luck,lump,lung,made,mail,main,male,mall,mama,many,maps,mark,mask,mass,mate,math,meal,mean,meat,meet,melt,memo,menu,mere,mesh,mess,mice,mild,mile,milk,mill,mind,mine,mini,mint,miss,mist,mock,mode,mold,mole,mood,moon,more,most,move,much,must,myth,nail,navy,near,neat,neck,need,nest,news,next,nice,nick,nine,node,none,noon,norm,nose,note,noun,null,nude,nuts,oath,obey,odds,okay,once,only,onto,open,oral,ours,oval,oven,over,pace,pack,page,paid,pain,pair,pale,palm,pane,park,part,pass,past,path,peak,pear,peer,pets,pick,pier,pike,pile,pill,pine,pink,pipe,pity,plan,play,plea,plot,plug,plus,poem,poet,poke,pole,poll,polo,pond,pool,poor,pope,pork,port,pose,post,pour,pray,prep,prey,prod,prof,prop,pull,pump,pure,push,quit,quiz,race,rack,rage,raid,rail,rain,rank,rare,rate,read,real,rear,rely,rent,repo,rest,rice,rich,ride,ring,riot,rise,risk,road,roar,rock,role,roll,roof,room,root,rope,rose,rows,ruby,rude,ruin,rule,rush,rust,sack,safe,sage,said,sail,sake,sale,salt,same,sand,sang,sank,save,scan,seal,seam,seat,sect,seed,seek,seem,seen,self,sell,semi,sept,ship,shoe,shop,shot,shut,sick,side,sigh,sign,silk,sing,sink,site,size,skin,skip,slam,slap,slim,slip,slot,slow,snap,snow,soap,soar,sock,soda,sofa,soft,soil,sold,sole,solo,some,song,soon,sort,soul,soup,sour,span,spin,spot,star,stat,stay,stem,step,stew,stop,such,sudo,suit,sums,sung,sunk,sure,surf,swan,swap,swim,sync,tabs,tack,tags,tail,tale,talk,tall,tank,tape,task,taxi,team,tear,tech,teen,tell,tend,tent,term,than,that,them,then,they,thin,this,thou,thus,tick,tide,tidy,tied,tier,tile,till,tilt,tiny,tire,toad,todo,told,toll,toml,tone,tony,took,tool,tops,tore,torn,toss,tour,town,trap,tray,tree,trek,trim,trio,trip,true,tube,tuck,tune,turn,twin,type,ugly,undo,unit,unix,upon,urge,used,uses,vain,vale,vast,veil,vein,verb,very,vest,veto,vice,vine,visa,void,vote,wade,wage,wait,wake,walk,wall,want,ward,warm,warn,warp,wars,wash,wasp,wave,ways,weak,wear,weed,weld,well,went,were,west,what,when,whip,whom,wide,wife,wild,will,wind,wine,wing,wink,wipe,wire,wise,wish,with,woke,wolf,wood,wool,word,wore,worm,worn,wrap,yaml,yard,yarn,yeah,yell,your,zero,zest,zone,zoom
apps,apis,args,auth,auto,axis,bare,barn,bash,bean,bind,blog,blur,boil,bolt,brew,bugs,bull,bump,bury,buzz,calm,cape,cart,cave,cent,chef,chew,chin,chop,cite,clam,clan,clap,claw,clay,clue,coil,cols,comb,cone,cork,corn,cozy,crab,crow,cube,cult,curb,cure,curl,cute,cyan,damp,dare,dart,deaf,deed,deem,demo,dent,devs,dime,dine,ding,dire,dish,dive,docs,doll,dome,doom,dots,dove,doze,drip,drum,duct,dude,duel,duet,duke,dull,dumb,dump,dune,dunk,dusk,earl,ears,echo,eyed,eyes,fake,fang,fans,fare,feat,fend,fern,fest,feud,fits,flap,flaw,flea,fled,flee,flex,flip,flit,foam,foil,fore,foul,fowl,fray,fret,frog,fume,funk,fury,fuse,fuss,fuzz,gait,gala,gale,gape,gasp,gaze,geek,germ,gild,gill,gist,glee,glen,glue,glum,glut,goat,gong,gore,gown,grab,grad,gram,grim,grin,grit,grub,gull,gulp,gush,gust,hail,hale,halo,halt,hare,harp,haste,haul,hawk,haze,hazy,heed,heel,heft,heir,helm,hemp,herd,hews,hive,hoax,hock,hone,hood,hoof,hoop,hoot,hose,howl,hubs,huff,hulk,hull,hump,hunk,hurl,hush,husk,hymn,hype,idol,iffy,inks,inns,iris,isle,itch,jade,jamb,jars,java,jaws,jeep,jeer,jell,jerk,jest,jets,jibe,jigs,jilt,jinx,jive,jolt,jots,jowl,joys,judo,jugs,july,junk,kale,keel,kelp,kerb,kiln,kilt,kink,kite,kith,kits,labs,lace,lacy,lads,lags,lair,lama,lamb,lame,laps,lard,lark,lash,lass,laud,lava,lawn,lays,leas,legs,lent,lest,lets,levy,lice,lids,lied,lien,lies,lieu,lilt,lily,limp,ling,lint,lisp,lite,loaf,lobe,lobs,lode,loft,loin,loom,loon,loop,loot,lope,lore,lout,lows,luau,lube,luff,lugs,lull,lune,lung,lure,lurk,lush,lute,lynx,lyre,mace,mach,mack,mage,magi,maid,malt,mane,mans,mare,marl,mars,mart,mash,mast,mats,maul,maze,mead,meek,meld,mend,meow,mete,mewl,mica,mick,midi,mink,minx,mire,mite,mitt,moan,moat,mobs,mods,moll,molt,moms,monk,mono,moor,moot,mope,mops,morn,moss,moth,mown,mows,muck,muff,mugs,mule,mull,murk,muse,mush,musk,mute,mutt,nabs,nags,nape,naps,nave,neon,nerd,nets,newt,nibs,nigh,nips,nite,nods,noir,nook,nosy,nova,nubs,nuke,numb,nuns,oafs,oaks,oars,oats,odes,odor,offs,ogle,ogre,oils,oily,oink,okra,olds,omen,omit,ones,onus,onyx,ooze,opal,opts,opus,orbs,orcs,ores,orgy,ouch,oust,outs,owed,owes,owls,owns,pact,pads,pail,pals,pang,pans,pant,papa,para,pare,pate,pats,pave,pawn,paws,pays,peal,peas,peat,peck,peek,peel,peep,peer,pegs,pelt,pend,pens,pent,peon,perk,perm,pert,pest,pews,phew,pied,pies,pigs,pimp,ping,pins,pint,pips,piss,pita,pith,pits,plat,pled,plod,plop,plot,plow,ploy,plum,pock,pods,poky,pols,poly,pomp,pone,pony,poop,pops,posh,posy,pots,pout,prig,prim,prod,prof,prom,pros,prow,pubs,puck,puds,puff,pugs,puke,pulp,puma,puns,punt,puny,pupa,pups,purl,purr,puss,puts,putt,pyre
looking,working,making,getting,coming,going,saying,using,having,doing,trying,asking,telling,showing,calling,running,building,testing,sending,writing,reading,playing,waiting,feeling,thinking,knowing,wanted,needed,looked,worked,called,asked,tried,turned,started,opened,closed,helped,played,moved,lived,loved,liked,hoped,planned,created,updated,deleted,checked,fixed,merged,pushed,pulled,cloned,logged,saved,loaded,failed,passed,works,makes,takes,gives,comes,goes,says,knows,thinks,looks,wants,uses,finds,tells,asks,seems,feels,tries,leaves,calls,needs,shows,plays,runs,moves,lives,helps,talks,turns,starts,keeps,holds,brings,writes,stands,hears,meets,pays,sits,sets,reads,sends,gets,puts,lets,better,best,first,second,third,fourth,fifth,sixth,always,never,often,sometimes,usually,already,almost,really,actually,finally,recently,quickly,slowly,clearly,easily,simply,directly,properly,correctly,together,between,through,without,within,against,around,behind,beyond,during,inside,outside,toward,before,after,while,until,unless,although,though,because,since,where,whether,however,therefore,moreover,meanwhile,otherwise,instead,anyway,perhaps,maybe,probably,certainly,definitely,obviously,especially,particularly,generally,specifically,basically,essentially,naturally,apparently,fortunately,unfortunately,hopefully,seriously,personally,completely,totally,entirely,absolutely,perfectly,exactly,precisely,roughly,nearly,approximately,mostly,mainly,largely,mostly,partly,slightly,fairly,pretty,rather,quite,enough,either,neither
""" + """
action,active,actual,add,adding,added,adjust,admin,admit,adopt,adult,advice,advise,after,again,agent,agree,ahead,alarm,album,alert,align,alike,alive,allow,almost,alone,along,alter,always,among,amount,anchor,angle,angry,animal,annual,answer,anyone,anyway,appeal,appear,apply,archive,argue,arise,around,arrive,arrow,article,artist,aspect,assert,assess,asset,assign,assist,assume,assure,attach,attack,attain,attempt,attend,audit,author,autumn,avail,avenue,avoid,awake,award,aware,awesome
backup,badge,balance,banner,barely,barrel,barrier,based,basic,basis,basket,batch,battery,beach,beauty,became,become,before,began,begin,begun,behalf,behave,behind,belief,believe,belong,below,bench,beneath,benefit,besides,better,between,beyond,bicycle,billion,binary,binding,biology,birthday,bishop,bitter,black,blade,blame,blank,blast,blend,bless,blind,block,blood,bloom,board,boast,bonus,boost,border,boring,borrow,bother,bottle,bottom,bought,bounce,bound,branch,brand,brave,bread,break,breath,breed,bridge,brief,bright,bring,broad,broke,broken,broker,bronze,brother,brought,brown,browse,browser,brush,bubble,bucket,budget,buffer,build,builder,built,bullet,bundle,burden,bureau,burger,burst,button,buyer
cabin,cable,cache,calendar,called,caller,camera,campus,cancel,cancer,candle,cannot,canvas,capable,capacity,capital,captain,capture,carbon,career,careful,carpet,carrier,carry,castle,casual,catch,category,caught,cause,cease,ceiling,celebrate,center,central,century,certain,chain,chair,challenge,chamber,chance,change,channel,chaos,chapter,charge,charity,charm,chart,chase,cheap,cheat,check,cheek,cheer,cheese,chemical,chest,chicken,chief,child,children,choice,choose,chosen,church,circle,circuit,citizen,civil,claim,class,classic,clause,clean,clear,clerk,click,client,climate,climb,clinic,clock,close,closer,closet,cloth,cloud,cluster,coach,coast,coding,coffee,cohort,collect,college,colony,color,column,combat,combine,comedy,comfort,command,comment,common,company,compare,compete,compile,complain,complete,complex,compose,compute,concept,concern,concert,conclude,concrete,conduct,confirm,conflict,conform,connect,consent,consider,consist,console,constant,consume,contact,contain,content,contest,context,continue,contract,contrast,control,convert,cookie,cooking,copper,corner,correct,costly,cotton,council,count,counter,country,county,couple,courage,course,court,cousin,cover,craft,crash,create,creator,credit,crisis,critic,cross,crowd,crown,crucial,cruel,crush,crystal,culture,curious,current,cursor,custom,cycle
daily,damage,danger,darkness,database,dataset,daughter,debate,decade,decide,decision,declare,decline,decode,default,defeat,defend,define,degree,delay,delete,deliver,demand,denied,denote,dense,depend,deposit,depth,derive,describe,desert,design,desire,desktop,despite,detail,detect,develop,device,devote,dialog,diamond,diary,dictate,differ,digest,digital,dinner,direct,director,disable,discard,discuss,disease,dismiss,display,dispute,distance,distinct,divide,doctor,document,domain,domestic,donate,double,doubt,download,dozen,draft,dragon,drama,drawn,dream,dress,drift,drill,drink,drive,driver,driven,during,dynamic
eager,early,earth,easily,eastern,eating,editor,effect,effort,eighth,either,elder,elect,element,elevate,eleven,emerge,emotion,emperor,employ,empty,enable,encode,encoder,ending,endless,endorse,energy,enforce,engage,engine,enhance,enjoy,enormous,enough,ensure,enter,entire,entity,entry,envelope,equal,equip,equity,error,escape,essay,estate,estimate,eternal,ethics,ethnic,europe,evaluate,evening,event,eventually,every,everyone,evidence,exact,examine,example,exceed,except,exchange,excite,exclude,excuse,execute,exempt,exercise,exhibit,exist,expand,expect,expense,expert,explain,explicit,explore,expose,express,extend,extent,external,extra,extract,extreme
fabric,facial,factor,factory,faculty,failed,failure,fairly,faith,fallen,family,famous,fancy,farmer,fashion,faster,father,fault,favor,favorite,feature,federal,feedback,feeling,fellow,female,fence,festival,fewer,fiber,fiction,field,fifteen,fifth,fifty,fight,figure,filter,final,finance,finding,finger,finish,finite,firing,fiscal,fitness,fixed,fixture,flame,flash,flavor,flight,float,flood,floor,flour,flower,flying,focus,folder,follow,forcing,foreign,forest,forever,forget,forgot,formal,format,former,formula,forth,fortune,forty,forum,forward,fossil,foster,fought,found,founder,fourth,frame,framework,frank,fraud,freedom,freeze,french,frequent,fresh,friend,front,frost,frozen,fruit,frustrate,fulfill,function,funding,funeral,funny,further,future
gadget,galaxy,gallery,gaming,garage,garden,gather,gender,general,generate,generic,genius,gentle,genuine,gesture,ghost,giant,gifted,given,glance,glass,global,globe,glory,glove,golden,govern,grace,grade,grain,grand,grant,graph,graphic,grasp,grass,grateful,grave,gravity,great,greater,green,greet,ground,group,growth,guard,guess,guest,guide,guilty,guitar
habit,handle,handler,happen,happy,harbor,hardly,hardware,harmful,harmony,harvest,hazard,header,health,healthy,hearing,heart,heater,heaven,heavy,height,hello,helper,helpful,heritage,hidden,higher,highly,highway,himself,history,hobby,holder,holiday,hollow,honest,honor,horizon,horror,horse,hospital,hostile,hotel,hourly,house,housing,however,human,humble,humor,hundred,hunger,hungry,hunter,hurry,hybrid
icon,ideal,identify,identity,ignore,illegal,illness,image,imagine,impact,implement,imply,import,impose,improve,impulse,incident,include,income,increase,indeed,index,indicate,indoor,industry,infant,inform,initial,inject,injury,inline,inner,input,inquiry,insert,inside,insight,insist,inspect,install,instance,instant,instead,instinct,intake,integer,intend,intense,intent,interact,interest,interim,internal,internet,interpret,interval,intimate,into,invade,invent,invest,invite,invoke,involve,island,issue,itself
jacket,japan,jealous,jewelry,joining,joint,journal,journey,judge,juice,junior,justice,justify
keeper,kernel,kettle,keynote,keyword,kidney,killer,kindly,kingdom,kitchen,knight,knock,known,korean
label,labor,ladder,ladies,landing,laptop,large,largely,larger,largest,laser,lasting,later,latest,latter,launch,laundry,lawyer,layer,layout,leader,leading,league,learn,lease,least,leather,leave,lecture,legacy,legal,legend,leisure,lemon,length,lessen,lesser,lesson,letter,level,liberal,liberty,library,license,lifetime,light,likely,limit,linear,linger,linker,liquid,listen,literal,little,lively,living,loaded,loader,local,locate,locked,locker,logic,london,lonely,longer,lookup,loose,lorry,losing,lounge,lovely,lover,lower,loyal,lucky,lunch,luxury
machine,madam,magic,magnet,mainly,maintain,major,maker,making,manage,manager,manner,manual,margin,marine,marker,market,married,marry,master,match,material,matrix,matter,mature,maximum,maybe,mayor,meaning,measure,mechanic,media,medical,medium,member,memory,mental,mention,mentor,merely,merger,merit,message,metal,meter,method,metric,middle,might,migrate,mildly,military,million,mineral,minimal,minimum,mining,minister,minor,minute,miracle,mirror,misery,missed,missing,mission,mistake,mobile,model,modern,modest,modify,module,moment,money,monitor,monkey,month,moral,moreover,morning,mortal,mostly,mother,motion,motive,motor,mount,mountain,mouse,mouth,moving,murder,muscle,museum,music,mutual,myself
namely,narrow,nation,native,natural,nature,nearby,nearly,neat,needed,needle,negative,neither,nephew,nerve,nervous,nested,network,neutral,never,newest,newly,newton,nicely,night,noble,nobody,noise,nominal,normal,north,northern,notable,notice,notify,notion,novel,nowhere,nuclear,number,numerous,nurse
object,oblige,observe,obtain,obvious,occasion,occupy,occur,ocean,october,offend,offer,office,officer,official,offset,often,older,olive,olympic,omega,online,opaque,opener,openly,operate,opinion,oppose,optic,optimal,option,oracle,orange,orbit,order,ordinary,organ,organic,orient,origin,other,ought,ounce,outcome,outdoor,outer,outfit,outlet,outline,output,outside,overall,overcome,overlap,overlook,oversea,owner,oxygen
package,packet,paddle,painter,palace,panel,panic,paper,parade,parcel,pardon,parent,parish,parking,partial,partner,party,passage,passing,passion,passive,patent,patient,patrol,pattern,pause,payment,peace,peanut,peasant,pencil,pending,penny,people,pepper,perceive,percent,perfect,perform,perhaps,period,permit,persist,person,personal,persuade,petrol,phase,phone,photo,physics,piano,pickup,picture,piece,pilot,pioneer,pitch,pixel,place,plain,planet,planned,planner,plant,plasma,plastic,plate,platform,player,please,pleasure,pledge,plenty,plugin,pocket,poetry,point,poison,police,policy,polish,polite,politic,pollute,ponder,popular,portal,portion,portrait,possess,possible,postal,poster,potato,potent,pound,powder,power,practice,praise,prayer,preach,predict,prefer,prefix,premier,prepare,present,preserve,press,pressure,pretty,prevent,preview,previous,price,pride,priest,primary,prime,prince,print,printer,prior,priority,prison,privacy,private,prize,probable,problem,proceed,process,produce,product,profile,profit,program,project,promise,promote,prompt,proper,property,propose,protect,protein,protest,proud,prove,provide,province,public,publish,pulling,punish,pupil,purchase,purely,purple,purpose,pursue,pushing,puzzle
qualify,quality,quarter,queen,query,quest,question,queue,quick,quickly,quiet,quietly,quite,quote
rabbit,racial,radar,radial,radio,radius,raise,rally,random,range,rapid,rarely,rather,rating,ratio,rational,reach,react,reader,ready,reality,realize,really,reason,rebel,rebuild,recall,receive,recent,recipe,reckon,record,recover,recruit,reduce,refer,reflect,reform,refuse,regard,regime,region,register,regret,regular,reject,relate,relax,release,relevant,relief,relieve,rely,remain,remark,remedy,remember,remind,remote,remove,render,rental,repair,repeat,replace,reply,report,request,require,rescue,research,reserve,reside,resign,resist,resolve,resort,resource,respect,respond,response,restore,result,resume,retail,retain,retire,return,reveal,revenue,reverse,review,revise,revive,reward,rhythm,ribbon,richer,rider,ridge,right,rigid,river,robot,robust,rocket,roller,roman,roof,roster,rotate,rough,roughly,round,route,routine,royal,rubber,ruling,runner,rural
sacred,saddle,safety,salary,sample,sanction,sand,satellite,satisfy,sauce,saving,scale,scanner,scare,scene,schedule,schema,scheme,scholar,school,science,scope,score,scout,screen,script,scroll,sculpt,sealed,season,second,secret,section,sector,secure,seeing,seeker,select,seller,senate,sender,senior,sense,sensor,sentence,separate,sequence,series,serious,servant,serve,server,service,session,settle,seven,several,severe,sexual,shade,shake,shall,shallow,shame,shape,share,sharp,sheet,shelf,shell,shelter,shield,shift,shine,shirt,shock,shoot,shore,short,should,shoulder,shout,shower,shrink,shuffle,shutter,sibling,sight,signal,signed,silent,silver,similar,simple,simply,singer,single,sister,sitting,sketch,skill,skirt,slave,sleep,sleeve,slice,slide,slight,slope,slowly,small,smart,smell,smile,smoke,smooth,snake,soccer,social,society,socket,software,solar,soldier,solely,solid,solution,solve,somehow,someone,source,south,southern,space,spare,spark,speak,speaker,special,species,specific,speech,speed,spend,sphere,spider,spine,spirit,splash,split,spoken,sponsor,sport,spouse,spread,spring,square,stable,stack,staff,stage,stake,stamp,stand,standard,stare,start,state,static,station,statue,status,steady,steal,steam,steel,steep,steer,stereo,stick,stiff,still,stock,stomach,stone,stood,store,storm,story,stove,strain,strand,strange,stranger,strategy,stream,street,strength,stress,stretch,strict,strike,string,strip,stroke,strong,struck,struct,struggle,student,stuff,stupid,style,subject,submit,subset,subtle,succeed,success,such,sudden,suffer,sugar,suggest,suite,summary,summer,summit,supply,support,suppose,supreme,surely,surface,surgery,surprise,surround,survey,survive,suspect,suspend,sustain,swear,sweep,sweet,swift,swing,switch,symbol,syntax,system
table,tablet,tackle,tactic,talent,talking,target,taught,teacher,teaching,teammate,temple,tenant,tender,tennis,tension,terminal,terrace,terrible,terror,testify,texture,thank,thanks,theater,theme,theory,therapy,thereby,thermal,thesis,thick,thief,thing,think,third,thirty,though,thought,thousand,thread,threat,three,throat,throne,through,throw,thumb,ticket,tight,timber,timing,tissue,title,today,together,toilet,token,tomato,tomorrow,tongue,tonight,topic,total,totally,touch,tough,toward,tower,trace,track,trade,traffic,tragedy,trail,train,trait,transfer,transit,translate,travel,treat,treaty,trend,trial,tribe,trick,trigger,triple,triumph,troop,tropical,trouble,truck,truly,trunk,trust,truth,trying,tunnel,turkey,turning,twelve,twenty,twice,twist,typical
unable,unfair,unfold,union,unique,united,unity,universe,unknown,unless,unlike,unlock,unpack,until,unusual,update,upgrade,uphold,upload,upper,upset,urban,urgent,usage,useful,user,usual,usually,utility
vacant,vacuum,vague,valid,valley,value,vanish,variety,various,vector,vehicle,vendor,venture,venue,verbal,verify,version,versus,vessel,veteran,viable,victim,victor,victory,video,viewer,village,violate,violent,virtual,virtue,virus,visible,vision,visit,visitor,visual,vital,vivid,vocal,voice,volume,voter
waiver,walker,wallet,wander,wanted,warden,warmth,warning,warrant,wealth,weapon,weather,weaver,website,wedding,weekly,weight,welcome,welfare,western,whale,whatever,wheat,wheel,where,whereas,whereby,whether,which,while,whilst,whisper,white,whole,wholly,whose,wicked,widely,widget,widow,width,willing,window,winner,winter,wisdom,within,without,witness,wizard,woman,women,wonder,wooden,worker,world,worry,worse,worst,worth,worthy,would,wound,writer,writing,written,wrong
yellow,yield,young,youth
zenith,zero,zodiac,zombie,zoning
github,kotlin,python,android,google,gradle,studio,docker,gemini,claude,typescript,javascript,flutter,chrome,apple,iphone,macbook,keyboard,shadow,phrase,config,commit,branch,merge,deploy,search,import,export,async,await,lambda,struct,trait,mutex,thread,socket,stream,buffer,router,render,layout,canvas,shader,vector,matrix,tensor,neuron,prompt,schema,parser,lexer,syntax,stdlib,module,bundle,daemon,worker,client,server,bridge,tunnel,proxy,cookie,header,footer,navbar,sidebar,drawer,modal,dialog,toast,banner,button,toggle,switch,slider,picker,scroll,cursor,keymap,hotkey,plugin,addon,script,macros,readme,changelog,license,assets,styles,themes,colors,fonts,icons,images,videos,audios,upload,signed,verify,tokens,secret,oauth,apikey,scopes,claims,cookie,cors,csrf,https,http,grpc,json,yaml,toml,html,css,scss,bash,zsh,linux,macos,windows,ubuntu,debian,brew,cargo,rustup,nodejs,npm,yarn,pnpm,react,nextjs,vue,vite,svelte,golang,golang,swift,xcode,csharp,dotnet,spring,django,flask,fastapi,pandas,numpy,scipy,torch,keras,mysql,sqlite,postgres,redis,mongo,kafka,nginx,caddy,traefik,vercel,netlify,heroku,aws,gcp,azure
"""
    }
}
