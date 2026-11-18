package com.typefree.core.ime.enhancer

import java.util.ArrayDeque
import java.util.Locale

/**
 * Compact 26-way ASCII Trie of common English words (`length >= 5`) used to veto premature
 * 4th-key reverse splitting / 5th-key push-commit when the user is typing an English word
 * inside `FIXED_YINXING` or `FIXED_SHAPECODE` schemas.
 */
class EnglishWordTrie(words: Collection<String>) {

    private class TrieNode {
        val children = arrayOfNulls<TrieNode>(26)
        var isWord: Boolean = false
        var hasChild: Boolean = false
    }

    private val root = TrieNode()

    init {
        for (raw in words) {
            val normalized = raw.trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty() || !isAsciiLowercase(normalized)) continue
            insert(normalized)
        }
    }

    private fun insert(word: String) {
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

    fun suggestWords(prefix: String, limit: Int = 8): List<String> {
        if (limit <= 0) return emptyList()
        val normalized = prefix.trim().lowercase(Locale.ROOT)
        val startNode = findNode(normalized) ?: return emptyList()

        val result = ArrayList<String>(limit)
        val queue = ArrayDeque<Pair<TrieNode, String>>()
        queue.add(startNode to normalized)

        while (queue.isNotEmpty() && result.size < limit) {
            val (node, text) = queue.removeFirst()
            if (node.isWord) {
                result.add(text)
                if (result.size >= limit) break
            }
            for (i in 0 until 26) {
                val child = node.children[i] ?: continue
                queue.add(child to (text + ('a' + i)))
            }
        }
        return result
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
                .filter { it.length >= 5 && isAsciiLowercase(it) }
                .distinct()
                .toList()
        }

        fun defaultBuiltIn(): EnglishWordTrie = EnglishWordTrie(DEFAULT_WORDS)

        private const val BUILTIN_WORD_BLOB = """
github,kotlin,python,android,google,gradle,studio,docker,gemini,claude,typescript,javascript,flutter,chrome,apple,iphone,macbook,keyboard,shadow,phrase,config,commit,branch,merge,deploy,search,import,export
about,above,abuse,accept,access,account,across,acting,action,active,actual,adapt,added,adjust,admin,admit,adopt,adult,advice,advise,affair,affect,afford,afraid,after,again,against,agent,agree,ahead,alarm,album,alert,align,alike,alive,allow,almost,alone,along,alter,always,among,amount,anchor,angle,angry,animal,annual,answer,anyone,anyway,appeal,appear,apply,apron,archive,argue,arise,around,arrive,arrow,article,artist,aspect,assert,assess,asset,assign,assist,assume,assure,attach,attack,attain,attempt,attend,audit,author,autumn,avail,avenue,avoid,awake,award,aware,awesome
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
"""
    }
}
