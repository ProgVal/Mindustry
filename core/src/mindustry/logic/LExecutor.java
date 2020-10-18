package mindustry.logic;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.noise.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicDisplay.*;
import mindustry.world.blocks.logic.MemoryBlock.*;
import mindustry.world.blocks.logic.MessageBlock.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class LExecutor{
    public static final int maxInstructions = 1000;

    //for noise operations
    public static final Simplex noise = new Simplex();

    //special variables
    public static final int
        varCounter = 0,
        varTime = 1,
        varUnit = 2,
        varThis = 3;

    public static final int
        maxGraphicsBuffer = 256,
        maxDisplayBuffer = 1024,
        maxTextBuffer = 256;

    public LInstruction[] instructions = {};
    public Var[] vars = {};

    public LongSeq graphicsBuffer = new LongSeq();
    public StringBuilder textBuffer = new StringBuilder();
    public Building[] links = {};
    public Team team = Team.derelict;

    public boolean initialized(){
        return instructions != null && vars != null && instructions.length > 0;
    }

    /** Runs a single instruction. */
    public void runOnce(){
        //set time
        vars[varTime].numval = Time.millis();

        //reset to start
        if(vars[varCounter].numval >= instructions.length
            || vars[varCounter].numval < 0) vars[varCounter].numval = 0;

        if(vars[varCounter].numval < instructions.length){
            instructions[(int)(vars[varCounter].numval++)].run(this);
        }
    }

    public void load(String data, int maxInstructions){
        load(LAssembler.assemble(data, maxInstructions));
    }

    /** Loads with a specified assembler. Resets all variables. */
    public void load(LAssembler builder){
        vars = new Var[builder.vars.size];
        instructions = builder.instructions;

        builder.vars.each((name, var) -> {
            Var dest = new Var(name);
            vars[var.id] = dest;

            dest.constant = var.constant;

            if(var.value instanceof Number){
                dest.isobj = false;
                dest.numval = ((Number)var.value).doubleValue();
            }else{
                dest.isobj = true;
                dest.objval = var.value;
            }
        });
    }

    //region utility

    public @Nullable Building building(int index){
        Object o = vars[index].objval;
        return vars[index].isobj && o instanceof Building ? (Building)o : null;
    }

    public @Nullable Object obj(int index){
        Object o = vars[index].objval;
        return vars[index].isobj ? o : null;
    }

    public boolean bool(int index){
        Var v = vars[index];
        return v.isobj ? v.objval != null : Math.abs(v.numval) >= 0.00001;
    }

    public double num(int index){
        Var v = vars[index];
        return v.isobj ? v.objval != null ? 1 : 0 : Double.isNaN(v.numval) || Double.isInfinite(v.numval) ? 0 : v.numval;
    }

    public float numf(int index){
        Var v = vars[index];
        return v.isobj ? v.objval != null ? 1 : 0 : Double.isNaN(v.numval) || Double.isInfinite(v.numval) ? 0 : (float)v.numval;
    }

    public int numi(int index){
        return (int)num(index);
    }

    public void setbool(int index, boolean value){
        setnum(index, value ? 1 : 0);
    }

    public void setnum(int index, double value){
        Var v = vars[index];
        if(v.constant) return;
        v.numval = Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
        v.objval = null;
        v.isobj = false;
    }

    public void setobj(int index, Object value){
        Var v = vars[index];
        if(v.constant) return;
        v.objval = value;
        v.isobj = true;
    }

    public void setconst(int index, Object value){
        Var v = vars[index];
        v.objval = value;
        v.isobj = true;
    }

    //endregion

    public static class Var{
        public final String name;

        public boolean isobj, constant;

        public Object objval;
        public double numval;

        public Var(String name){
            this.name = name;
        }
    }

    //region instruction types

    public interface LInstruction{
        void run(LExecutor exec);
    }

    /** Binds the processor to a unit based on some filters. */
    public static class UnitBindI implements LInstruction{
        public int type;

        //iteration index
        private int index;

        public UnitBindI(int type){
            this.type = type;
        }

        public UnitBindI(){
        }

        @Override
        public void run(LExecutor exec){

            //binding to `null` was previously possible, but was too powerful and exploitable
            if(exec.obj(type) instanceof UnitType){
                UnitType unit_type = (UnitType) exec.obj(type);
                Seq<Unit> seq = exec.team.data().unitCache(unit_type);

                if(seq != null && seq.any()){
                    index %= seq.size;
                    if(index < seq.size){
                        //bind to the next unit
                        exec.setconst(varUnit, seq.get(index));
                    }
                    index ++;
                }else{
                    //no units of this type found
                    exec.setconst(varUnit, null);
                }
            }else{
                exec.setconst(varUnit, null);
            }
        }
    }

    /** Uses a unit to find something that may not be in its range. */
    public static class UnitLocateI implements LInstruction{
        public LLocate locate = LLocate.building;
        public BlockFlag flag = BlockFlag.core;
        public int enemy, ore;
        public int outX, outY, outFound;

        public UnitLocateI(LLocate locate, BlockFlag flag, int enemy, int ore, int outX, int outY, int outFound){
            this.locate = locate;
            this.flag = flag;
            this.enemy = enemy;
            this.ore = ore;
            this.outX = outX;
            this.outY = outY;
            this.outFound = outFound;
        }

        public UnitLocateI(){
        }

        @Override
        public void run(LExecutor exec){
            Object unitObj = exec.obj(varUnit);
            LogicAI ai = UnitControlI.checkLogicAI(exec, unitObj);

            if(unitObj instanceof Unit && ai != null){
                Unit unit = (Unit) unitObj;
                ai.controlTimer = LogicAI.logicControlTimeout;

                Cache cache = (Cache)ai.execCache.get(this, Cache::new);

                if(ai.checkTargetTimer(this)){
                    Tile res = null;
                    boolean build = false;

                    switch(locate){
                        case ore:
                            if(exec.obj(ore) instanceof Item){
                                Item item = (Item) exec.obj(ore);
                                res = indexer.findClosestOre(unit.x, unit.y, item);
                            }
                            break;
                        case building:
                            res = Geometry.findClosest(unit.x, unit.y, exec.bool(enemy) ? indexer.getEnemy(unit.team, flag) : indexer.getAllied(unit.team, flag));
                            build = true;
                            break;
                        case spawn:
                            res = Geometry.findClosest(unit.x, unit.y, Vars.spawner.getSpawns());
                            break;
                        case damaged:
                            Building b = Units.findDamagedTile(unit.team, unit.x, unit.y);
                            res = b == null ? null : b.tile;
                            build = true;
                            break;
                    }

                    if(res != null && (!build || res.build != null)){
                        cache.found = true;
                        //set result if found
                        exec.setnum(outX, cache.x = World.conv(build ? res.build.x : res.worldx()));
                        exec.setnum(outY, cache.y = World.conv(build ? res.build.y : res.worldy()));
                        exec.setnum(outFound, 1);
                    }else{
                        cache.found = false;
                        exec.setnum(outFound, 0);
                    }
                }else{
                    exec.setbool(outFound, cache.found);
                    exec.setnum(outX, cache.x);
                    exec.setnum(outY, cache.y);
                }
            }
        }

        static class Cache{
            float x, y;
            boolean found;
        }
    }

    /** Controls the unit based on some parameters. */
    public static class UnitControlI implements LInstruction{
        public LUnitControl type = LUnitControl.move;
        public int p1, p2, p3, p4;

        public UnitControlI(LUnitControl type, int p1, int p2, int p3, int p4){
            this.type = type;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.p4 = p4;
        }

        public UnitControlI(){
        }

        /** Checks is a unit is valid for logic AI control, and returns the controller. */
        @Nullable
        public static LogicAI checkLogicAI(LExecutor exec, Object unitObj){
            if(unitObj instanceof Unit) {
                Unit unit = (Unit) unitObj;
                if (exec.obj(varUnit) == unit && unit.team == exec.team && !unit.isPlayer() && !(unit.controller() instanceof FormationAI)){
                    if(!(unit.controller() instanceof LogicAI)){
                        unit.controller(new LogicAI());
                        ((LogicAI)unit.controller()).controller = exec.building(varThis);

                        //clear old state
                        if(unit instanceof Minerc){
                            Minerc miner =(Minerc) unit;
                            miner.mineTile(null);
                        }

                        if(unit instanceof Builderc){
                            Builderc builder = (Builderc) unit;
                            builder.clearBuilding();
                        }

                        return (LogicAI)unit.controller();
                    }
                    return (LogicAI)unit.controller();
                }
            }
            return null;
        }

        @Override
        public void run(LExecutor exec){
            Object unitObj = exec.obj(varUnit);
            LogicAI ai = checkLogicAI(exec, unitObj);

            //only control standard AI units
            if(unitObj instanceof Unit && ai != null){
                Unit unit = (Unit) unitObj;
                ai.controlTimer = LogicAI.logicControlTimeout;
                float x1 = World.unconv(exec.numf(p1)), y1 = World.unconv(exec.numf(p2)), d1 = World.unconv(exec.numf(p3));
                Building build;
                int amount;

                switch(type){
                    case move: case stop: case approach:
                        ai.control = type;
                        ai.moveX = x1;
                        ai.moveY = y1;
                        if(type == LUnitControl.approach){
                            ai.moveRad = d1;
                        }

                        //stop mining/building
                        if(type == LUnitControl.stop){
                            if(unit instanceof Minerc){
                                Minerc miner = (Minerc) unit;
                                miner.mineTile(null);
                            }
                            if(unit instanceof Builderc){
                                Builderc builder = (Builderc) unit;
                                builder.clearBuilding();
                            }
                        }
                        break;
                    case within:
                        exec.setnum(p4, unit.within(x1, y1, d1) ? 1 : 0);
                        break;
                    case pathfind:
                        ai.control = type;
                        break;
                    case target:
                        ai.posTarget.set(x1, y1);
                        ai.aimControl = type;
                        ai.mainTarget = null;
                        ai.shoot = exec.bool(p3);
                        break;
                    case targetp:
                        ai.aimControl = type;
                        ai.mainTarget = exec.obj(p1) instanceof Teamc ? (Teamc) exec.obj(p1) : null;
                        ai.shoot = exec.bool(p2);
                        break;
                    case boost:
                        ai.boost = exec.bool(p1);
                        break;
                    case flag:
                        unit.flag = exec.num(p1);
                        break;
                    case mine:
                        Tile tile = world.tileWorld(x1, y1);
                        if(unit instanceof Minerc){
                            Minerc miner = (Minerc) unit;
                            miner.mineTile(miner.validMine(tile) ? tile : null);
                        }
                        break;
                    case payDrop:
                        if(ai.payTimer > 0) return;

                        if(unit instanceof Payloadc && ((Payloadc) unit).hasPayload()){
                            Call.payloadDropped(unit, unit.x, unit.y);
                            ai.payTimer = LogicAI.transferDelay;
                        }
                        break;
                    case payTake:
                        if(ai.payTimer > 0) return;

                        if(unit instanceof Payloadc){
                            Payloadc pay = (Payloadc) unit;
                            //units
                            if(exec.bool(p1)){
                                Unit result = Units.closest(unit.team, unit.x, unit.y, unit.type.hitSize * 2f, u -> u.isAI() && u.isGrounded() && pay.canPickup(u) && u.within(unit, u.hitSize + unit.hitSize * 1.2f));

                                if(result != null){
                                    Call.pickedUnitPayload(unit, result);
                                }
                            }else{ //buildings
                                Building tile2 = world.buildWorld(unit.x, unit.y);

                                //TODO copy pasted code
                                if(tile2 != null && tile2.team == unit.team){
                                    if(tile2.block.buildVisibility != BuildVisibility.hidden && tile2.canPickup() && pay.canPickup(tile2)){
                                        Call.pickedBuildPayload(unit, tile2, true);
                                    }else{ //pick up block payload
                                        Payload current = tile2.getPayload();
                                        if(current != null && pay.canPickupPayload(current)){
                                            Call.pickedBuildPayload(unit, tile2, false);
                                        }
                                    }
                                }
                            }
                            ai.payTimer = LogicAI.transferDelay;
                        }
                        break;
                    case build:
                        if(unit instanceof Builderc && exec.obj(p3) instanceof Block){
                            Builderc builder = (Builderc) unit;
                            Block block = (Block) exec.obj(p3);
                            int x = World.toTile(x1), y = World.toTile(y1);
                            int rot = exec.numi(p4);

                            //reset state of last request when necessary
                            if(ai.plan.x != x || ai.plan.y != y || ai.plan.block != block || builder.plans().isEmpty()){
                                ai.plan.progress = 0;
                                ai.plan.initialized = false;
                                ai.plan.stuck = false;
                            }

                            ai.plan.set(x, y, rot, block);
                            ai.plan.config = null;

                            if(ai.plan.tile() != null){
                                builder.clearBuilding();
                                builder.updateBuilding(true);
                                builder.addBuild(ai.plan);
                            }
                        }
                        break;
                    case getBlock:
                        float range = Math.max(unit.range(), buildingRange);
                        if(!unit.within(x1, y1, range)){
                            exec.setobj(p3, null);
                            exec.setnum(p4, 0);
                        }else{
                            Tile tile2 = world.tileWorld(x1, y1);
                            //any environmental solid block is returned as StoneWall, aka "@solid"
                            Block block = tile2 == null ? null : !tile2.synthetic() ? (tile2.solid() ? Blocks.stoneWall : Blocks.air) : tile2.block();
                            exec.setobj(p3, block);
                            exec.setnum(p4, tile2 != null && tile2.build != null ? tile2.build.rotation : 0);
                        }
                        break;
                    case itemDrop:
                        if(ai.itemTimer > 0) return;

                        build = exec.building(p1);
                        amount = exec.numi(p2);
                        int dropped = Math.min(unit.stack.amount, amount);
                        if(build != null && dropped > 0 && unit.within(build, logicItemTransferRange)){
                            int accepted = build.acceptStack(unit.item(), dropped, unit);
                            if(accepted > 0){
                                Call.transferItemTo(unit, unit.item(), accepted, unit.x, unit.y, build);
                                ai.itemTimer = LogicAI.transferDelay;
                            }
                        }
                        break;
                    case itemTake:
                        if(ai.itemTimer > 0) return;

                        build = (Building) exec.building(p1);
                        amount = exec.numi(p3);

                        if(build != null && exec.obj(p2) instanceof Item && unit.within(build, logicItemTransferRange)){
                            Item item = (Item) exec.obj(p2);
                            int taken = Math.min(build.items.get(item), Math.min(amount, unit.maxAccepted(item)));

                            if(taken > 0){
                                Call.takeItems(build, item, taken, unit);
                                ai.itemTimer = LogicAI.transferDelay;
                            }
                        }
                        break;
                    default: break;
                }
            }
        }
    }

    /** Controls a building's state. */
    public static class ControlI implements LInstruction{
        public int target;
        public LAccess type = LAccess.enabled;
        public int p1, p2, p3, p4;

        public ControlI(LAccess type, int target, int p1, int p2, int p3, int p4){
            this.type = type;
            this.target = target;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.p4 = p4;
        }

        ControlI(){}

        @Override
        public void run(LExecutor exec){
            Object obj = exec.obj(target);
            if(obj instanceof Controllable){
                Controllable cont = (Controllable) obj;
                if(type.isObj){
                    cont.control(type, exec.obj(p1), exec.num(p2), exec.num(p3), exec.num(p4));
                }else{
                    cont.control(type, exec.num(p1), exec.num(p2), exec.num(p3), exec.num(p4));
                }
            }
        }
    }

    public static class GetLinkI implements LInstruction{
        public int output, index;

        public GetLinkI(int output, int index){
            this.index = index;
            this.output = output;
        }

        public GetLinkI(){
        }

        @Override
        public void run(LExecutor exec){
            int address = exec.numi(index);

            exec.setobj(output, address >= 0 && address < exec.links.length ? exec.links[address] : null);
        }
    }

    public static class ReadI implements LInstruction{
        public int target, position, output;

        public ReadI(int target, int position, int output){
            this.target = target;
            this.position = position;
            this.output = output;
        }

        public ReadI(){
        }

        @Override
        public void run(LExecutor exec){
            int address = exec.numi(position);
            Building from = exec.building(target);

            if(from instanceof MemoryBuild){
                MemoryBuild mem = (MemoryBuild) from;

                exec.setnum(output, address < 0 || address >= mem.memory.length ? 0 : mem.memory[address]);
            }
        }
    }

    public static class WriteI implements LInstruction{
        public int target, position, value;

        public WriteI(int target, int position, int value){
            this.target = target;
            this.position = position;
            this.value = value;
        }

        public WriteI(){
        }

        @Override
        public void run(LExecutor exec){
            int address = exec.numi(position);
            Building from = exec.building(target);

            if(from instanceof MemoryBuild){
                MemoryBuild mem = (MemoryBuild) from;

                if(address >= 0 && address < mem.memory.length){
                    mem.memory[address] = exec.num(value);
                }

            }
        }
    }

    public static class SenseI implements LInstruction{
        public int from, to, type;

        public SenseI(int from, int to, int type){
            this.from = from;
            this.to = to;
            this.type = type;
        }

        public SenseI(){
        }

        @Override
        public void run(LExecutor exec){
            Object target = exec.obj(from);
            Object sense = exec.obj(type);

            if(target instanceof Senseable){
                Senseable se = (Senseable) target;
                if(sense instanceof Content){
                    exec.setnum(to, se.sense(((Content)sense)));
                }else if(sense instanceof LAccess){
                    Object objOut = se.senseObject((LAccess)sense);

                    if(objOut == Senseable.noSensed){
                        //numeric output
                        exec.setnum(to, se.sense((LAccess)sense));
                    }else{
                        //object output
                        exec.setobj(to, objOut);
                    }
                }
            }else{
                exec.setnum(to, 0);
            }
        }
    }

    public static class RadarI implements LInstruction{
        public RadarTarget target1 = RadarTarget.enemy, target2 = RadarTarget.any, target3 = RadarTarget.any;
        public RadarSort sort = RadarSort.distance;
        public int radar, sortOrder, output;

        //radar instructions are special in that they cache their output and only change it at fixed intervals.
        //this prevents lag from spam of radar instructions
        public Healthc lastTarget;
        public Interval timer = new Interval();

        static float bestValue = 0f;
        static Unit best = null;

        public RadarI(RadarTarget target1, RadarTarget target2, RadarTarget target3, RadarSort sort, int radar, int sortOrder, int output){
            this.target1 = target1;
            this.target2 = target2;
            this.target3 = target3;
            this.sort = sort;
            this.radar = radar;
            this.sortOrder = sortOrder;
            this.output = output;
        }

        public RadarI(){
        }

        @Override
        public void run(LExecutor exec){
            Object base = exec.obj(radar);

            int sortDir = exec.bool(sortOrder) ? 1 : -1;
            LogicAI ai = null;

            if(base instanceof Ranged && ((Ranged) base).team() == exec.team &&
                (base instanceof Building || (ai = UnitControlI.checkLogicAI(exec, base)) != null)){ //must be a building or a controllable unit
                Ranged r = (Ranged) base;
                float range = r.range();

                Healthc targeted;

                //timers update on a fixed 30 tick interval
                //units update on a special timer per controller instance
                if((base instanceof Building && timer.get(30f)) || (ai != null && ai.checkTargetTimer(this))){
                    //if any of the targets involve enemies
                    boolean enemies = target1 == RadarTarget.enemy || target2 == RadarTarget.enemy || target3 == RadarTarget.enemy;

                    best = null;
                    bestValue = 0;

                    if(enemies){
                        Seq<TeamData> data = state.teams.present;
                        for(int i = 0; i < data.size; i++){
                            if(data.items[i].team != r.team()){
                                find(r, range, sortDir, data.items[i].team);
                            }
                        }
                    }else{
                        find(r, range, sortDir, r.team());
                    }

                    lastTarget = targeted = best;
                }else{
                    targeted = lastTarget;
                }

                exec.setobj(output, targeted);
            }else{
                exec.setobj(output, null);
            }
        }

        void find(Ranged b, float range, int sortDir, Team team){
            Units.nearby(team, b.x(), b.y(), range, u -> {
                if(!u.within(b, range)) return;

                boolean valid =
                    target1.func.get(b.team(), u) &&
                    target2.func.get(b.team(), u) &&
                    target3.func.get(b.team(), u);

                if(!valid) return;

                float val = sort.func.get(b, u) * sortDir;
                if(val > bestValue || best == null){
                    bestValue = val;
                    best = u;
                }
            });
        }
    }

    public static class SetI implements LInstruction{
        public int from, to;

        public SetI(int from, int to){
            this.from = from;
            this.to = to;
        }

        SetI(){}

        @Override
        public void run(LExecutor exec){
            Var v = exec.vars[to];
            Var f = exec.vars[from];

            //TODO error out when the from-value is a constant
            if(!v.constant){
                if(f.isobj){
                    v.objval = f.objval;
                    v.isobj = true;
                }else{
                    v.numval = Double.isNaN(f.numval) || Double.isInfinite(f.numval) ? 0 : f.numval;
                    v.isobj = false;
                }
            }
        }
    }

    public static class OpI implements LInstruction{
        public LogicOp op = LogicOp.add;
        public int a, b, dest;

        public OpI(LogicOp op, int a, int b, int dest){
            this.op = op;
            this.a = a;
            this.b = b;
            this.dest = dest;
        }

        OpI(){}

        @Override
        public void run(LExecutor exec){
            if(op.unary){
                exec.setnum(dest, op.function1.get(exec.num(a)));
            }else{
                Var va = exec.vars[a];
                Var vb = exec.vars[b];

                if(op.objFunction2 != null && (va.isobj || vb.isobj)){
                    //use object function if provided, and one of the variables is an object
                    exec.setnum(dest, op.objFunction2.get(exec.obj(a), exec.obj(b)));
                }else{
                    //otherwise use the numeric function
                    exec.setnum(dest, op.function2.get(exec.num(a), exec.num(b)));
                }

            }
        }
    }

    public static class EndI implements LInstruction{

        @Override
        public void run(LExecutor exec){
            exec.vars[varCounter].numval = exec.instructions.length;
        }
    }

    public static class NoopI implements LInstruction{
        @Override
        public void run(LExecutor exec){}
    }

    public static class DrawI implements LInstruction{
        public byte type;
        public int target;
        public int x, y, p1, p2, p3, p4;

        public DrawI(byte type, int target, int x, int y, int p1, int p2, int p3, int p4){
            this.type = type;
            this.target = target;
            this.x = x;
            this.y = y;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.p4 = p4;
        }

        public DrawI(){
        }

        @Override
        public void run(LExecutor exec){
            //graphics on headless servers are useless.
            if(Vars.headless) return;

            //add graphics calls, cap graphics buffer size
            if(exec.graphicsBuffer.size < maxGraphicsBuffer){
                exec.graphicsBuffer.add(DisplayCmd.get(type, exec.numi(x), exec.numi(y), exec.numi(p1), exec.numi(p2), exec.numi(p3), exec.numi(p4)));
            }
        }
    }

    public static class DrawFlushI implements LInstruction{
        public int target;

        public DrawFlushI(int target){
            this.target = target;
        }

        public DrawFlushI(){
        }

        @Override
        public void run(LExecutor exec){
            //graphics on headless servers are useless.
            if(Vars.headless) return;

            Building build = exec.building(target);
            if(build instanceof LogicDisplayBuild){
                LogicDisplayBuild d = (LogicDisplayBuild) build;
                if(d.commands.size + exec.graphicsBuffer.size < maxDisplayBuffer){
                    for(int i = 0; i < exec.graphicsBuffer.size; i++){
                        d.commands.addLast(exec.graphicsBuffer.items[i]);
                    }
                }
                exec.graphicsBuffer.clear();
            }
        }
    }

    public static class PrintI implements LInstruction{
        public int value;

        public PrintI(int value){
            this.value = value;
        }

        PrintI(){}

        @Override
        public void run(LExecutor exec){

            if(exec.textBuffer.length() >= maxTextBuffer) return;

            //this should avoid any garbage allocation
            Var v = exec.vars[value];
            if(v.isobj && value != 0){
                String strValue =
                    v.objval == null ? "null" :
                    v.objval instanceof String ? (String)v.objval :
                    v.objval instanceof Content ? "[content]" :
                    v.objval instanceof Building ? "[building]" :
                    v.objval instanceof Unit ? "[unit]" :
                    "[object]";

                exec.textBuffer.append(strValue);
            }else{
                //display integer version when possible
                if(Math.abs(v.numval - (long)v.numval) < 0.000001){
                    exec.textBuffer.append((long)v.numval);
                }else{
                    exec.textBuffer.append(v.numval);
                }
            }
        }
    }

    public static class PrintFlushI implements LInstruction{
        public int target;

        public PrintFlushI(int target){
            this.target = target;
        }

        public PrintFlushI(){
        }

        @Override
        public void run(LExecutor exec){

            Building build = exec.building(target);
            if(build instanceof MessageBuild){
                MessageBuild d = (MessageBuild) build;

                d.message.setLength(0);
                d.message.append(exec.textBuffer, 0, Math.min(exec.textBuffer.length(), maxTextBuffer));

                exec.textBuffer.setLength(0);
            }
        }
    }

    public static class JumpI implements LInstruction{
        public ConditionOp op = ConditionOp.notEqual;
        public int value, compare, address;

        public JumpI(ConditionOp op, int value, int compare, int address){
            this.op = op;
            this.value = value;
            this.compare = compare;
            this.address = address;
        }

        public JumpI(){
        }

        @Override
        public void run(LExecutor exec){
            if(address != -1){
                Var va = exec.vars[value];
                Var vb = exec.vars[compare];
                boolean cmp;

                if(op.objFunction != null && (va.isobj || vb.isobj)){
                    //use object function if provided, and one of the variables is an object
                    cmp = op.objFunction.get(exec.obj(value), exec.obj(compare));
                }else{
                    cmp = op.function.get(exec.num(value), exec.num(compare));
                }

                if(cmp){
                    exec.vars[varCounter].numval = address;
                }
            }
        }
    }

    //endregion
}
