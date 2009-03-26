/*
 * Copyright (c) 1998-2009 Caucho Technology -- all rights reserved
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
 * @author Nam Nguyen
 */

package com.caucho.quercus.lib.regexp;

import com.caucho.quercus.env.Env;
import com.caucho.quercus.env.StringValue;
import com.caucho.quercus.env.Value;

/**
 * XXX: experimental
 */
public class PregSplitProxy
{
  //5 to start seeing respectable hit rates in wordpress
  private final PregSplitResult []_results = new PregSplitResult[5];
  
  private int _hits;
  private int _total;
  private boolean _isPassthru;
  
  private final int MIN_HITS = 5;
  private final int MAX_TOTAL = 20;
  
  public PregSplitProxy()
  {
  }
  
  public Value preg_split(Env env, Regexp regexp, StringValue subject,
                          long limit, int flags)
  {
    if (_isPassthru)
      return RegexpModule.preg_split(env, regexp, subject, limit, flags);
    else if (_total >= MAX_TOTAL && _hits < MIN_HITS) {
      _isPassthru = true;
      
      for (int i = 0; i < _results.length; i++) {
        _results[i] = null;
      }
      
      return RegexpModule.preg_split(env, regexp, subject, limit, flags);
    }
    
    for (int i = 0; i < _results.length; i++) {
      PregSplitResult result = _results[i];
      
      if (result == null) {
        Value val
          = RegexpModule.preg_split(env, regexp, subject, limit, flags);
        
        _results[i] = new PregSplitResult(regexp, subject, limit, flags, val);
        
        return val;
      }
      else if (result.equals(regexp, subject, limit, flags)) {
        if (_total < MAX_TOTAL) {
          _total++;
          _hits++;
        }

        return result.get();
      }
    }
    
    if (_total < MAX_TOTAL)
      _total++;
    
    return RegexpModule.preg_split(env, regexp, subject, limit, flags);
  }
  
  static class PregSplitResult
  {
    final Regexp _regexp;
    final StringValue _subject;
    final long _limit;
    final int _flags;
    
    final Value _result;
    
    PregSplitResult(Regexp regexp, StringValue subject,
                    long limit, int flags,
                    Value result)
    {
      _regexp = regexp;
      _subject = subject;
      _limit = limit;
      _flags = flags;
      _result = result.copy();
    }
    
    public boolean equals(Regexp regexp, StringValue subject,
                          long limit, int flags)
    {
      return regexp == _regexp && limit == _limit && flags == _flags
             && subject.equals(_subject);
    }
    
    public Value get()
    {
      return _result.copy();
    }
  }
}
