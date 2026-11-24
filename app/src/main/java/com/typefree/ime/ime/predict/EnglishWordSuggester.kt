package com.typefree.ime.ime.predict

import com.typefree.core.ime.enhancer.EnglishWordTrie
import com.typefree.ime.ime.EnglishSmartTyper
import java.util.Locale

data class EnglishSuggestionResult(
    val prefix: String,
    val replaceLength: Int,
    val suggestions: List<String>
)

/**
 * Extracts a trailing ASCII English prefix (length `2..24`, preceded by start/whitespace/punctuation)
 * and produces case-adapted word completions backed by [EnglishWordTrie].
 */
class EnglishWordSuggester(
    private val customTrie: EnglishWordTrie? = null
) {

    fun suggestForBeforeCursor(
        textBeforeCursor: String,
        limit: Int = 6
    ): EnglishSuggestionResult? {
        if (limit <= 0) return null
        val prefix = extractTrailingEnglishPrefix(textBeforeCursor) ?: return null
        val words = suggestWords(prefix = prefix, limit = limit)
        if (words.isEmpty()) return null
        return EnglishSuggestionResult(
            prefix = prefix,
            replaceLength = prefix.length,
            suggestions = words
        )
    }

    fun suggestWords(prefix: String, limit: Int = 6): List<String> {
        if (limit <= 0) return emptyList()
        val trimmed = prefix.trim()
        if (trimmed.length !in MIN_PREFIX_LEN..MAX_PREFIX_LEN) return emptyList()
        if (!trimmed.all(::isAsciiLetter)) return emptyList()

        val lowerPrefix = trimmed.lowercase(Locale.ROOT)
        val smartReplacement = EnglishSmartTyper.resolveReplacement(trimmed)
            ?.takeIf { it != trimmed }
        val rawCandidates = LinkedHashSet<String>(limit * 2)

        if (customTrie != null) {
            rawCandidates.addAll(customTrie.suggestWords(lowerPrefix, limit = limit * 2))
        } else {
            rawCandidates.addAll(SHORT_HIGH_FREQ_TRIE.suggestWords(lowerPrefix, limit = limit * 2))
            if (rawCandidates.size < limit * 2) {
                rawCandidates.addAll(CORE_DEFAULT_TRIE.suggestWords(lowerPrefix, limit = limit * 2))
            }
        }

        if (rawCandidates.isEmpty() && smartReplacement == null) return emptyList()

        val result = ArrayList<String>(limit)
        if (smartReplacement != null) {
            result.add(smartReplacement)
        }
        rawCandidates
            .asSequence()
            .sortedWith(compareBy<String>({ it == lowerPrefix }, { it.length }, { it }))
            .map { adaptCase(word = it, prefix = trimmed) }
            .filter { it != smartReplacement }
            .take((limit - result.size).coerceAtLeast(0))
            .forEach { result.add(it) }
        return result
    }

    companion object {
        const val MIN_PREFIX_LEN = 2
        const val MAX_PREFIX_LEN = 24

        private val CORE_DEFAULT_TRIE: EnglishWordTrie by lazy {
            EnglishWordTrie.defaultBuiltIn()
        }

        private val SHORT_HIGH_FREQ_TRIE: EnglishWordTrie by lazy {
            EnglishWordTrie(
                SHORT_AND_COMMON_WORDS
                    .lineSequence()
                    .flatMap { it.splitToSequence(',', ' ', '\t') }
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } }
                    .distinct()
                    .toList()
            )
        }

        fun extractTrailingEnglishPrefix(textBeforeCursor: String): String? {
            if (textBeforeCursor.isEmpty()) return null
            var idx = textBeforeCursor.length - 1
            if (!isAsciiLetter(textBeforeCursor[idx])) return null

            while (idx > 0 && isAsciiLetter(textBeforeCursor[idx - 1])) {
                idx--
            }
            val prefix = textBeforeCursor.substring(idx)
            if (prefix.length !in MIN_PREFIX_LEN..MAX_PREFIX_LEN) return null

            if (idx > 0) {
                val prev = textBeforeCursor[idx - 1]
                if (!prev.isWhitespace() && !isBoundaryPunctuation(prev)) {
                    return null
                }
            }
            return prefix
        }

        fun adaptCase(word: String, prefix: String): String {
            if (prefix.isEmpty() || word.isEmpty()) return word
            val allUpper = prefix.all { it in 'A'..'Z' }
            if (allUpper) return word.uppercase(Locale.ROOT)
            val titleCase = prefix[0] in 'A'..'Z' && prefix.drop(1).all { it in 'a'..'z' }
            if (titleCase) {
                return word.replaceFirstChar { it.uppercaseChar() }
            }
            return word.lowercase(Locale.ROOT)
        }

        private fun isAsciiLetter(ch: Char): Boolean {
            return ch in 'a'..'z' || ch in 'A'..'Z'
        }

        private fun isBoundaryPunctuation(ch: Char): Boolean {
            if (ch == '_') return false
            val type = Character.getType(ch)
            if (
                type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt()
            ) {
                return true
            }
            return ch in "+=<>|~^`$"
        }

        private const val SHORT_AND_COMMON_WORDS = """
am,an,as,at,be,by,do,go,he,hi,if,in,is,it,me,my,no,of,ok,on,or,so,to,up,us,we,
add,age,ago,air,all,and,any,api,app,are,arm,art,ask,bad,bag,bar,bat,bay,bed,bee,big,bit,box,boy,bug,bus,but,buy,bye,
can,cap,car,cat,cmd,cpu,cry,cup,cut,dad,day,dev,did,die,dig,doc,dog,dot,dry,due,dug,ear,eat,egg,end,env,era,err,eve,eye,
fan,far,fat,fax,fee,few,fig,fit,fix,fly,fog,for,fox,fun,fur,gap,gas,gay,get,git,god,got,gpu,gun,guy,gym,
had,ham,has,hat,her,hey,hid,him,his,hit,hot,how,hub,hue,hug,ice,icy,ide,ill,ink,ion,ios,its,jam,jar,jaw,jet,job,jog,joy,key,kid,kin,kit,
lab,lad,lag,lap,law,lay,led,leg,let,lid,lie,lip,lit,log,lot,low,mad,man,map,mat,max,may,men,met,mid,min,mix,mob,mod,mom,mop,mud,mug,
nap,net,new,nil,nod,nor,not,now,nut,oak,odd,off,oil,old,one,opt,our,out,owe,owl,own,
pad,pal,pan,par,pat,pay,pen,per,pet,pie,pig,pin,pit,pod,pop,pot,pro,pub,pun,put,
ram,ran,rap,rat,raw,ray,red,rep,rid,rig,rim,rip,rob,rod,row,rub,rug,run,
sad,sat,saw,say,sea,see,set,she,shy,sin,sip,sir,sit,six,ski,sky,sly,sob,sod,son,sow,soy,spa,spy,sql,src,sub,sue,sum,sun,sup,
tab,tag,tan,tap,tar,tax,tea,ten,the,tie,tin,tip,toe,ton,too,top,tow,toy,try,tub,tug,two,
url,usb,use,van,vat,vet,via,vow,war,was,wax,way,web,wed,wet,who,why,wig,win,wit,woe,wok,won,woo,wow,yak,yam,yes,yet,you,zip,zoo,
able,acid,aged,also,area,army,atom,aunt,auto,away,baby,back,ball,band,bank,base,bath,beam,bean,bear,beat,been,beer,bell,belt,bend,best,beta,bike,bill,bind,bird,bite,blog,blow,blue,blur,boat,body,bold,bolt,bomb,bond,bone,book,boom,boot,bore,born,boss,both,bowl,bulk,bull,burn,bury,bush,busy,byte,
cafe,cage,cake,calf,call,calm,came,camp,card,care,case,cash,cast,cave,cell,chat,chef,chin,chip,cite,city,clad,clam,clap,clay,clip,club,clue,coal,coat,code,coil,coin,cold,cole,comb,come,cone,cook,cool,cope,copy,cord,core,cork,corn,cost,cozy,crew,crop,crow,cube,cure,curl,cute,
damp,dare,dark,dash,data,date,dawn,days,dead,deaf,deal,dean,dear,debt,deck,deed,deep,deer,demo,deny,desk,dial,dice,diet,dime,dine,dirt,disc,dish,disk,dive,dock,does,doll,dome,done,doom,door,dose,down,drag,draw,drew,drop,drug,drum,dual,duck,duke,dull,dumb,dump,dune,dusk,dust,duty,
each,earn,ease,east,easy,edge,edit,else,emit,ends,envy,epic,even,ever,evil,exam,exec,exit,expo,face,fact,fade,fail,fair,fake,fall,fame,fare,farm,fast,fate,fear,feat,feed,feel,feet,fell,felt,file,fill,film,find,fine,fire,firm,fish,fist,five,flag,flat,fled,flee,flew,flex,flip,flow,foam,fold,folk,fond,font,food,fool,foot,ford,fore,fork,form,fort,foul,four,free,frog,from,fuel,full,fume,fund,fuse,
gain,gale,game,gang,gate,gave,gaze,gear,gene,gift,girl,give,glad,glow,glue,goal,goat,goes,gold,golf,gone,good,gown,grab,grad,gram,gray,grew,grid,grim,grin,grip,grow,gulf,guru,
hack,hail,hair,half,hall,halt,hand,hang,hard,hare,harm,harp,hash,hate,haul,have,hawk,haze,head,heal,heap,hear,heat,heed,heel,held,hell,helm,help,herb,herd,here,hero,hide,high,hike,hill,hint,hire,hold,hole,holy,home,hone,hood,hook,hope,horn,hose,host,hour,huge,hull,hung,hunt,hurt,hush,
icon,idea,idle,idol,inch,info,init,into,iron,isle,item,jack,jade,jail,java,jazz,jean,jeep,jerk,jest,join,joke,jolt,json,jump,june,junk,jury,just,
keen,keep,kept,kick,kids,kill,kind,king,kiss,kite,knee,knew,knit,knob,knot,know,
lace,lack,lady,laid,lake,lamb,lame,lamp,land,lane,lard,lark,last,late,lava,lawn,lazy,lead,leaf,leak,lean,leap,left,lend,lens,less,liar,lick,life,lift,like,limb,lime,limp,line,link,lion,list,live,load,loaf,loan,lock,loft,logo,lone,long,look,loop,lord,lore,lose,loss,lost,loud,love,luck,lump,lung,lure,lurk,lush,lust,
made,maid,mail,main,make,male,mall,malt,mama,mane,many,maps,mark,mars,mask,mass,mast,mate,math,maze,meal,mean,meat,meek,meet,meld,melt,memo,mend,menu,mere,mesh,mess,meta,mild,mile,milk,mill,mind,mine,mint,miss,mist,moan,moat,mock,mode,mold,mole,molt,monk,mood,moon,moor,more,moss,most,moth,move,much,muck,mule,muse,mush,must,mute,myth,
nail,name,navy,near,neat,neck,need,nest,news,next,nice,nick,nine,node,none,noon,norm,nose,note,noun,nude,nuke,null,numb,
oath,obey,odds,odor,okay,omen,omit,once,only,onto,open,oral,orca,oven,over,owed,owns,pace,pack,pact,page,paid,pail,pain,pair,pale,palm,pane,pang,pant,papa,park,part,pass,past,path,pave,pawn,peak,pear,peas,peat,peck,peek,peel,peer,pelt,pend,perk,perm,pest,pets,pick,pier,pike,pile,pill,pine,pink,pint,pipe,pity,plan,play,plea,pled,plot,plow,ploy,plug,plum,plus,poem,poet,poke,pole,poll,polo,pond,pony,pool,poor,pope,pork,port,pose,post,pour,pray,prep,prey,prod,prof,prom,prop,prow,pull,pulp,pump,punk,pure,purr,push,putt,
quad,quit,quiz,race,rack,rage,raid,rail,rain,rake,ramp,rang,rank,rant,rare,rash,rate,rave,read,real,ream,reap,rear,redo,reed,reef,reek,reel,rely,rend,rent,rest,rice,rich,ride,rift,rile,rill,rind,ring,riot,ripe,rise,risk,road,roam,roar,robe,rock,rode,role,roll,roof,rook,room,root,rope,rose,rosy,rote,rout,rove,ruby,rude,ruin,rule,rump,rung,runt,ruse,rush,rust,ruth,
sack,safe,saga,sage,said,sail,sake,sale,salt,same,sand,sane,sang,sank,sash,save,scan,scar,seal,seam,sear,seat,sect,seed,seek,seem,seen,seep,self,sell,semi,send,sent,sept,sera,serf,sets,sewn,sexy,shad,sham,shed,shin,ship,shoe,shoo,shop,shot,show,shun,shut,sick,side,sift,sigh,sign,silk,sill,silo,silt,sine,sing,sink,sire,site,size,skew,skid,skim,skin,skip,skit,slab,slag,slam,slap,slat,slay,sled,slew,slid,slim,slip,slit,slob,slog,slop,slot,slow,slug,slum,slur,smog,snap,snag,snip,snob,snow,snub,snug,soak,soap,soar,sock,soda,sofa,soft,soil,sold,sole,solo,some,song,soon,soot,sore,sort,soul,soup,sour,span,spar,spat,spec,sped,spin,spit,spot,spry,spun,spur,stab,stag,star,stay,stem,step,stew,stir,stop,stow,stub,stud,stun,such,suck,suit,sulk,sung,sunk,sure,surf,swan,swap,swat,sway,swim,swum,sync,
tabs,tack,tact,tail,take,tale,talk,tall,tame,tang,tank,tape,taps,tare,tarn,taro,tarp,tart,task,taxi,teak,teal,team,tear,tech,teem,teen,tell,temp,tend,tens,tent,term,test,text,than,that,thaw,thee,them,then,they,thin,this,thou,thud,thug,thus,tick,tide,tidy,tied,tier,tile,till,tilt,time,tine,tint,tiny,tire,toad,toil,told,toll,tomb,tome,tone,tong,took,tool,toot,tops,tore,torn,tort,toss,tote,tour,tout,town,toys,tram,trap,tray,tree,trek,trim,trio,trip,trod,trot,true,tube,tuck,tuft,tuna,tune,turf,turn,tusk,tutu,twig,twin,twit,type,
ugly,undo,unit,unto,upon,urea,urge,used,user,uses,vain,vale,vane,vary,vase,vast,veal,veer,veil,vein,vend,vent,verb,very,vest,veto,vial,vice,view,vile,vine,visa,void,volt,vote,wade,wage,wail,wait,wake,walk,wall,wand,wane,want,ward,ware,warm,warn,warp,wart,wary,wash,wasp,wave,wavy,waxy,weak,weal,wean,wear,weed,week,weep,weld,well,welt,went,wept,were,west,what,when,whim,whip,whir,whom,wick,wide,wife,wild,will,wilt,wily,wimp,wind,wine,wing,wink,wipe,wire,wiry,wise,wish,wisp,with,wits,woke,wolf,womb,wont,wood,woof,wool,word,wore,work,worm,worn,wort,wrap,wren,writ,
yaml,yank,yard,yarn,yawn,yeah,year,yell,yelp,yoga,yoke,yolk,your,yowl,yule,zeal,zero,zest,zinc,zone,zoom
"""
    }
}
