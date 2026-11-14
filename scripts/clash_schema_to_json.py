import re, json, sys

src = open(sys.argv[1], encoding='utf-8').read()
# only from the first const after the EditorSchema enum
start = src.index('const _str = ')
body = src[start:]

# ---- tokenizer
tok_re = re.compile(r'''
 (?P<ws>\s+|//[^\n]*)
|(?P<str>r?'(?:[^'\\\n]|\\.)*'|r?"(?:[^"\\\n]|\\.)*")
|(?P<num>\d+)
|(?P<id>[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)
|(?P<spread>\.\.\.)
|(?P<p>[()\[\]{}:,;<>=?])
''', re.X)
toks=[]; pos=0
while pos < len(body):
    m = tok_re.match(body, pos)
    if not m: raise SystemExit('bad token at %d: %r' % (pos, body[pos:pos+40]))
    pos = m.end()
    if m.lastgroup == 'ws': continue
    toks.append((m.lastgroup, m.group()))

def unstr(t):
    raw = t.startswith('r')
    if raw: t = t[1:]
    q = t[0]; inner = t[1:-1]
    if raw: return inner
    out=[]; i=0
    while i < len(inner):
        c=inner[i]
        if c=='\\':
            n=inner[i+1]
            out.append({'n':'\n','t':'\t','$':'$','\\':'\\',"'":"'",'"':'"'}.get(n, n)); i+=2
        else: out.append(c); i+=1
    return ''.join(out)

# ---- split top level declarations: const|final NAME = expr ;
decls={}
i=0
while i < len(toks):
    t=toks[i]
    if t[1] in ('const','final'):
        # name may follow type args; find '=' 
        j=i+1
        name=toks[j][1]
        assert toks[j+1][1]=='=', (name, toks[j+1])
        # expr until ';' at depth 0
        k=j+2; depth=0; expr=[]
        while True:
            x=toks[k]
            if x[1] in '([{' and x[0]=='p': depth+=1
            if x[1] in ')]}' and x[0]=='p': depth-=1
            if x[1]==';' and depth==0: break
            expr.append(x); k+=1
        decls[name]=expr
        i=k+1
    else:
        raise SystemExit('unexpected top-level token %r near %r' % (t, toks[i:i+6]))

class Node:
    def __init__(s, kind, **kw):
        s.kind=kind; s.fields=kw.get('fields',{}); s.variants=kw.get('variants',{})
        s.entry=kw.get('entry'); s.values=kw.get('values',[]); s.scalar=kw.get('scalar','plain')

cache={}
resolving=set()
def ref(name):
    if name in cache: return cache[name]
    if name in resolving: raise SystemExit('cycle '+name)
    resolving.add(name)
    v=P(decls[name]).expr()
    resolving.discard(name)
    cache[name]=v
    return v

class P:
    def __init__(s, toks): s.t=toks; s.i=0
    def peek(s): return s.t[s.i] if s.i < len(s.t) else (None,None)
    def next(s): x=s.t[s.i]; s.i+=1; return x
    def accept(s, v):
        if s.peek()[1]==v and s.peek()[0] in ('p','spread'): s.i+=1; return True
        return False
    def skip_typeargs(s):
        if s.peek()[1]=='<':
            d=0
            while True:
                x=s.next()
                if x[1]=='<': d+=1
                if x[1]=='>':
                    d-=1
                    if d==0: break
    def expr(s):
        v=s.primary()
        assert s.i==len(s.t), ('trailing', s.t[s.i:s.i+5])
        return v
    def primary(s):
        s.skip_typeargs()
        k,v=s.peek()
        if k=='str':
            parts=[]
            while s.peek()[0]=='str': parts.append(unstr(s.next()[1]))
            return ''.join(parts)
        if k=='num': return int(s.next()[1])
        if v=='[': return s.listlit()
        if v=='{': return s.maplit()
        if k=='id':
            s.next()
            if s.peek()[1]=='(': return s.call(v)
            if v.startswith('YamlScalar.'): return ('enum', v.split('.')[1])
            if v=='GroupName.GLOBAL.name': return 'GLOBAL'
            return ref(v)
        raise SystemExit('primary? %r' % ((k,v),))
    def listlit(s):
        s.next(); out=[]
        while not s.accept(']'):
            if s.accept('...'): out.extend(s.primary())
            else: out.append(s.primary())
            s.accept(',')
        return out
    def maplit(s):
        s.next(); items=[]; is_map=None
        d={}; lst=[]
        while not s.accept('}'):
            if s.accept('...'):
                v=s.primary()
                if isinstance(v,dict): d.update(v); is_map=True
                else: lst.extend(v); is_map=False
            else:
                a=s.primary()
                if s.accept(':'):
                    d[a]=s.primary(); is_map=True
                else: lst.append(a); is_map=False
            s.accept(',')
        return d if is_map or is_map is None else lst
    def call(s, name):
        s.next()  # (
        pos=[]; named={}
        while not s.accept(')'):
            if s.peek()[0]=='id' and s.t[s.i+1][1]==':' :
                n=s.next()[1]; s.next(); named[n]=s.primary()
            else: pos.append(s.primary())
            s.accept(',')
        if name=='YamlSchema.scalar': return Node('s', values=pos[0] if pos else [])
        if name=='YamlSchema.of': return Node('s', scalar=pos[0][1], values=pos[1] if len(pos)>1 else [])
        if name=='YamlSchema.map': return Node('m', fields=pos[0], variants=named.get('variants',{}))
        if name=='YamlSchema.dict': return Node('m', entry=pos[0])
        if name=='YamlSchema.list': return Node('l', entry=pos[0])
        raise SystemExit('call? '+name)

root=ref('clashConfigSchema')
extras={k:ref(k) for k in ('ruleTypes','noResolveRuleTypes','rulePayloadValues','ruleOptions')}

# ---- serialize with sharing: nodes used more than once go into defs
count={}
def walk(n):
    count[id(n)]=count.get(id(n),0)+1
    if count[id(n)]>1: return
    for f in n.fields.values(): walk(f)
    for var in n.variants.values():
        for f in var.values(): walk(f)
    if n.entry: walk(n.entry)
walk(root)
defs={}; ids={}
def ser(n, top=False):
    if not top and count[id(n)]>1 and n.kind!='s' :
        if id(n) not in ids:
            ids[id(n)]='d%d'%len(ids); defs[ids[id(n)]]=None
            defs[ids[id(n)]]=ser(n, True)
        return {'r':ids[id(n)]}
    o={'k':n.kind}
    if n.fields: o['f']={k:ser(v) for k,v in n.fields.items()}
    if n.variants: o['v']={t:{k:ser(v) for k,v in var.items()} for t,var in n.variants.items()}
    if n.entry: o['e']=ser(n.entry)
    if n.values: o['x']=n.values
    if n.scalar!='plain': o['s']=n.scalar
    return o
out={'root':ser(root,True),'defs':defs,
     'ruleTypes':extras['ruleTypes'],'noResolve':sorted(extras['noResolveRuleTypes']) if isinstance(extras['noResolveRuleTypes'],list) else extras['noResolveRuleTypes'],
     'payloads':extras['rulePayloadValues'],'ruleOptions':extras['ruleOptions']}
text=json.dumps(out,ensure_ascii=False,separators=(',',':'))
open(sys.argv[2],'w',encoding='utf-8').write(text)
print('decls',len(decls),'defs',len(defs),'bytes',len(text.encode()),'top keys',len(root.fields))
