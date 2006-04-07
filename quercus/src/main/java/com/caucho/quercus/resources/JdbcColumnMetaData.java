/*
 * Copyright (c) 1998-2006 Caucho Technology -- all rights reserved
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

package com.caucho.quercus.resources;

import java.sql.*;

/**
 * Represents a JDBC column metadata
 */
public class JdbcColumnMetaData {
  private final JdbcTableMetaData _table;

  private final String _name;

  private final int _jdbcType;

  private final int _length;

  private final boolean _isNotNull;
  private final boolean _isUnsigned;
  private final boolean _isZeroFill;

  private boolean _isPrimaryKey;
  private boolean _isIndex;
  private boolean _isUnique;

  /**
   * @param rs the ResultSet from a DatabaseMetaData.getColumns call
   */
  public JdbcColumnMetaData(JdbcTableMetaData table, ResultSet rs)
    throws SQLException
  {
    _table = table;

    _name = rs.getString("COLUMN_NAME");
    _jdbcType = rs.getInt("DATA_TYPE");
    _length = rs.getInt("COLUMN_SIZE");

    _isNotNull = rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;

    String type = rs.getString("TYPE_NAME").toLowerCase();

    _isUnsigned = type.indexOf("unsigned") >= 0;
    _isZeroFill = type.indexOf("zerofill") >= 0;
  }

  /**
   * Returns the column's name.
   */
  public String getName()
  {
    return _name;
  }

  /**
   * Returns the column's table
   */
  public JdbcTableMetaData getTable()
  {
    return _table;
  }

  /**
   * Returns the column length.
   */
  public int getLength()
  {
    return _length;
  }

  /**
   * Returns true if the column is nullable.
   */
  public boolean isNotNull()
  {
    return _isNotNull;
  }

  /**
   * Returns true for a primary key.
   */
  public boolean isPrimaryKey()
  {
    return _isPrimaryKey;
  }

  /**
   * Set true for a primary key.
   */
  void setPrimaryKey(boolean isPrimaryKey)
  {
    _isPrimaryKey = isPrimaryKey;
  }

  /**
   * Returns true for an index
   */
  public boolean isIndex()
  {
    return _isIndex;
  }

  /**
   * Set true for an index
   */
  void setIndex(boolean isIndex)
  {
    _isIndex = isIndex;
  }

  /**
   * Returns true for a unique column
   */
  public boolean isUnique()
  {
    return _isUnique;
  }

  /**
   * Set true for a unique column
   */
  void setUnique(boolean isUnique)
  {
    _isUnique = isUnique;
  }

  /**
   * Returns the JDBC type.
   */
  public int getJdbcType()
  {
    return _jdbcType;
  }

  /**
   * Returns true for numeric data types.
   */
  public boolean isNumeric()
  {
    switch (_jdbcType) {
    case Types.BIT:
    case Types.TINYINT:
    case Types.SMALLINT:
    case Types.INTEGER:
    case Types.BIGINT:
    case Types.DOUBLE:
    case Types.FLOAT:
    case Types.REAL:
      return true;
    default:
      return false;
    }
  }

  /**
   * Returns true for float data types.
   */
  public boolean isFloat()
  {
    switch (_jdbcType) {
    case Types.DOUBLE:
    case Types.FLOAT:
    case Types.REAL:
      return true;
    default:
      return false;
    }
  }

  /**
   * Returns true for unsigned.
   */
  public boolean isUnsigned()
  {
    return _isUnsigned;
  }

  /**
   * Returns true for zerofill
   */
  public boolean isZeroFill()
  {
    return _isZeroFill;
  }

  /**
   * Returns true for blob data types.
   */
  public boolean isBlob()
  {
    switch (_jdbcType) {
      // php/142z
    case Types.LONGVARBINARY:
    case Types.BLOB:
    case Types.CLOB:
      return true;
    default:
      return false;
    }
  }

  public String toString()
  {
    return "JdbcColumnMetaData[" + getName() + "]";
  }
}

