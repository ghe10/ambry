package com.github.ambry.store;

import com.github.ambry.store.IndexKey;

/**
 * Created with IntelliJ IDEA.
 * User: srsubram
 * Date: 10/26/13
 * Time: 2:00 PM
 * To change this template use File | Settings | File Templates.
 */
public interface IndexKeyFactory {
  IndexKey getKey(String value);
}
