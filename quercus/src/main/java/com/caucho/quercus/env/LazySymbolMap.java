/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.quercus.env;

import com.caucho.java.LineMap;
import com.caucho.java.ScriptStackTrace;
import com.caucho.java.WorkDir;
import com.caucho.loader.SimpleLoader;
import com.caucho.quercus.Location;
import com.caucho.quercus.Quercus;
import com.caucho.quercus.expr.Expr;
import com.caucho.quercus.page.QuercusPage;
import com.caucho.util.L10N;
import com.caucho.util.IntMap;
import com.caucho.vfs.WriteStream;

import java.util.*;
import java.util.logging.Logger;

/**
 * Represents the Quercus environment.
 */
public class LazySymbolMap extends AbstractMap<String,EnvVar> {
  private final IntMap _intMap;
  private final Value []_values;
  
  private HashMap<String,EnvVar> _extMap = new HashMap<String,EnvVar>();

  public LazySymbolMap(IntMap intMap, Value []values)
  {
    _intMap = intMap;
    _values = values;
  }

  /**
   * Returns the matching value, or null.
   */
  public EnvVar get(Object key)
  {
    return (EnvVar) get((String) key);
  }

  /**
   * Returns the matching value, or null.
   */
  public EnvVar get(String key)
  {
    EnvVar envVar = _extMap.get(key);

    if (envVar == null) {
      int id = _intMap.get(key);

      if (id >= 0 && _values[id] != null) {
	envVar = new EnvVarImpl(new Var());
	_extMap.put(key, envVar);
      
	Env env = Env.getCurrent();
	
	Value value = _values[id].copy(env);

	envVar.set(value);
      }
    }
    
    return envVar;
  }

  /**
   * Returns the matching value, or null.
   */
  @Override
  public EnvVar put(String key, EnvVar newVar)
  {
    return _extMap.put(key, newVar);
  }

  public Set<Map.Entry<String,EnvVar>> entrySet()
  {
    return _extMap.entrySet();
  }
}

