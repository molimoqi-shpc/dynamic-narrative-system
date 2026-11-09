-- V2 read-only actual-stat verifier. Inputs are existing base fields; no persisted values change.
WITH normalized AS (
 SELECT god_id,
 LEAST(10000,GREATEST(0,COALESCE(base_zhi,0))) z,
 LEAST(10000,GREATEST(0,COALESCE(base_hui,0))) h,
 LEAST(10000,GREATEST(0,COALESCE(base_dou,0))) d,
 LEAST(10000,GREATEST(0,COALESCE(base_wen,0))) w,
 LEAST(100,GREATEST(0,COALESCE(base_ben,0))) b,
 LEAST(10000,GREATEST(0,COALESCE(base_gui,0))) g
 FROM god_profile
), curves AS (
 SELECT god_id,b,
 CASE WHEN z<=100 THEN z ELSE 100e0+50e0*LN(1e0+(z-100e0)/50e0) END cz,
 CASE WHEN h<=100 THEN h ELSE 100e0+50e0*LN(1e0+(h-100e0)/50e0) END ch,
 CASE WHEN d<=100 THEN d ELSE 100e0+50e0*LN(1e0+(d-100e0)/50e0) END cd,
 CASE WHEN w<=100 THEN w ELSE 100e0+50e0*LN(1e0+(w-100e0)/50e0) END cw,
 CASE WHEN g<=100 THEN g ELSE 100e0+50e0*LN(1e0+(g-100e0)/50e0) END cg
 FROM normalized
), harmonic AS (
 SELECT *, 1e0+0.6e0*b/(b+50e0) m,
 CASE WHEN ch+cw=0 THEN 0 ELSE 2e0*ch*cw/(ch+cw) END hhw,
 CASE WHEN cz+cw=0 THEN 0 ELSE 2e0*cz*cw/(cz+cw) END hzw,
 CASE WHEN ch+cg=0 THEN 0 ELSE 2e0*ch*cg/(ch+cg) END hhg,
 CASE WHEN cz+ch=0 THEN 0 ELSE 2e0*cz*ch/(cz+ch) END hzh
 FROM curves
)
SELECT god_id,
 ROUND(cz*m*(1e0+0.2e0*hhw/(hhw+50e0))) actual_zhi,
 ROUND(ch*m*(1e0+0.2e0*hzw/(hzw+50e0))) actual_hui,
 ROUND(cd*m*(1e0+0.12e0*hhg/(hhg+50e0))) actual_dou,
 ROUND(cw*m*(1e0+0.2e0*hzh/(hzh+50e0))) actual_wen,
 ROUND(b*(1e0+0.15e0*hzh/(hzh+50e0))) actual_ben,
 ROUND(cg*(1e0+0.15e0*hzh/(hzh+50e0))) actual_gui
FROM harmonic ORDER BY god_id;
